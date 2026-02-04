package com.j8d.karate.debug;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.j8d.karate.debug.backend.Breakpoint;
import com.j8d.karate.debug.backend.BreakpointRequest;
import com.j8d.karate.debug.backend.EvaluateResult;
import com.j8d.karate.debug.backend.Scope;
import com.j8d.karate.debug.backend.SetVariableResult;
import com.j8d.karate.debug.backend.StackFrame;
import com.j8d.karate.debug.backend.Variable;
import com.j8d.karate.debug.coordinator.DebugCoordinator;
import com.j8d.karate.debug.decompiler.DecompilationService;
import com.j8d.karate.debug.multiplexer.MultiplexerEventListener;
import com.j8d.karate.debug.process.ChildProcessConfig;

/**
 * DAP session handler for unified polyglot debugging.
 *
 * Uses DebugCoordinator to manage debugging across Karate, JavaScript, and Java
 * in a single unified session. This spawns Karate in a child process with
 * debug agents for all three languages.
 */
public class PolyglotDapSession implements MultiplexerEventListener, OutputEventSender {

    private static final Logger log = LoggerFactory.getLogger(PolyglotDapSession.class);
    private static final String CONTENT_LENGTH = "Content-Length: ";

    private final Socket socket;
    private final String workspaceRoot;
    private final String karateEnv;
    private final String classpath;
    private final String logLevel;
    private final String sourcePaths;  // Semicolon-separated list of additional source directories/archives
    private final Gson gson;
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);

    // Dedicated lock for message sending to avoid deadlock with DapOutputAppender
    // The problem: log.trace() -> DapOutputAppender -> sendMessage (synchronized on this)
    // If main thread is processing messages and holds 'this' lock, other threads block on logging
    private final Object sendLock = new Object();

    private BufferedReader reader;
    private OutputStream writer;
    private volatile boolean running = false;

    private DebugCoordinator coordinator;
    private ChildProcessConfig config;
    private CompletableFuture<Void> initializationFuture;

    // Launch configuration
    private String featurePath;
    private boolean enableJavaDebugging = false;
    private boolean enableJsDebugging = false;

    // Pre-launch breakpoints: VS Code sends setBreakpoints before launch, but coordinator
    // doesn't exist yet. We queue them here and transfer to coordinator when launch is processed.
    private final List<PreLaunchBreakpoints> preLaunchBreakpoints = new ArrayList<>();

    // Record to store pre-launch breakpoints (path + requests)
    private record PreLaunchBreakpoints(String filePath, List<BreakpointRequest> requests) {}

    // Decompilation support for viewing framework source code
    private DecompilationService decompilationService;

    // Source reference tracking for external sources (JARs, decompiled classes)
    // When a source file doesn't exist locally, we assign it a sourceReference ID
    // so VS Code will request the content via the "source" DAP request
    private final AtomicInteger nextSourceReference = new AtomicInteger(1);
    private final Map<Integer, String> sourceRefToPath = new ConcurrentHashMap<>();
    private final Map<String, Integer> pathToSourceRef = new ConcurrentHashMap<>();

    // Cache for extracted source files from zip archives
    // Maps "archive!relativePath" to the extracted temp file path
    private final Map<String, String> extractedSourceCache = new ConcurrentHashMap<>();
    private java.io.File tempSourceDir;

    public PolyglotDapSession(Socket socket, String workspaceRoot, String karateEnv, String classpath, String logLevel, String sourcePaths) {
        this.socket = socket;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
        this.classpath = classpath;
        this.logLevel = logLevel;
        this.sourcePaths = sourcePaths;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Run the DAP session.
     * @return true if at least one valid DAP message was processed
     */
    public boolean run() {
        boolean hadValidMessage = false;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = socket.getOutputStream();
            running = true;

            while (running) {
                JsonObject message = readMessage();
                if (message == null) {
                    log.trace("No more messages, ending session");
                    break;
                }
                hadValidMessage = true;
                handleMessage(message);
            }
            log.trace("Session loop ended, running={}, hadValidMessage={}", running, hadValidMessage);
        } catch (IOException e) {
            log.error("Session error", e);
        } finally {
            cleanup();
        }
        return hadValidMessage;
    }

    private JsonObject readMessage() throws IOException {
        int contentLength = -1;
        String line;

        log.trace("Waiting for message...");
        while ((line = reader.readLine()) != null) {
            log.trace("Header line: '{}'", line);
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith(CONTENT_LENGTH)) {
                contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }

        if (line == null) {
            log.trace("Connection closed by client");
            return null;
        }

        if (contentLength < 0) {
            log.warn("No Content-Length header found");
            return null;
        }

        char[] buffer = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(buffer, read, contentLength - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }

        String json = new String(buffer);
        log.trace("Received: {}", json);
        return gson.fromJson(json, JsonObject.class);
    }

    public void sendMessage(JsonObject message) {
        // Serialize outside the lock to minimize lock hold time and avoid
        // logging while holding the lock (which can cause deadlocks with logback's
        // synchronized doAppend() when trace level is enabled)
        String json = gson.toJson(message);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = (CONTENT_LENGTH + jsonBytes.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);

        // Log BEFORE acquiring the lock to avoid deadlock with logback's appender synchronization
        log.trace("Sending: {}", json);

        // Use dedicated sendLock instead of synchronized(this) to avoid deadlock
        // when DapOutputAppender calls this from logging threads
        synchronized (sendLock) {
            try {
                writer.write(header);
                writer.write(jsonBytes);
                writer.flush();
            } catch (IOException e) {
                log.error("Error sending message", e);
            }
        }
    }

    public void sendResponse(JsonObject request, boolean success, JsonObject body) {
        JsonObject response = new JsonObject();
        response.addProperty("seq", sequenceNumber.getAndIncrement());
        response.addProperty("type", "response");
        response.addProperty("request_seq", request.get("seq").getAsInt());
        response.addProperty("command", request.get("command").getAsString());
        response.addProperty("success", success);
        if (body != null) {
            response.add("body", body);
        }
        sendMessage(response);
    }

    public void sendEvent(String event, JsonObject body) {
        JsonObject message = new JsonObject();
        message.addProperty("seq", sequenceNumber.getAndIncrement());
        message.addProperty("type", "event");
        message.addProperty("event", event);
        if (body != null) {
            message.add("body", body);
        }
        sendMessage(message);
    }

    private void handleMessage(JsonObject message) {
        String type = message.get("type").getAsString();
        if (!"request".equals(type)) {
            return;
        }

        String command = message.get("command").getAsString();
        JsonObject args = message.has("arguments") ? message.getAsJsonObject("arguments") : new JsonObject();

        switch (command) {
            case "initialize" -> handleInitialize(message, args);
            case "launch" -> handleLaunch(message, args);
            case "setBreakpoints" -> handleSetBreakpoints(message, args);
            case "configurationDone" -> handleConfigurationDone(message);
            case "threads" -> handleThreads(message);
            case "stackTrace" -> handleStackTrace(message, args);
            case "scopes" -> handleScopes(message, args);
            case "variables" -> handleVariables(message, args);
            case "setVariable" -> handleSetVariable(message, args);
            case "source" -> handleSource(message, args);
            case "continue" -> handleContinue(message, args);
            case "next" -> handleNext(message, args);
            case "stepIn" -> handleStepIn(message, args);
            case "stepOut" -> handleStepOut(message, args);
            case "evaluate" -> handleEvaluate(message, args);
            case "disconnect" -> handleDisconnect(message);
            default -> sendResponse(message, true, null);
        }
    }

    private void handleInitialize(JsonObject request, JsonObject args) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("supportsConfigurationDoneRequest", true);
        capabilities.addProperty("supportsFunctionBreakpoints", false);
        capabilities.addProperty("supportsConditionalBreakpoints", true);
        capabilities.addProperty("supportsEvaluateForHovers", true);
        capabilities.addProperty("supportsSetVariable", true);
        capabilities.addProperty("supportsStepBack", false);
        capabilities.addProperty("supportsRestartFrame", false);
        capabilities.addProperty("supportsGotoTargetsRequest", false);
        capabilities.addProperty("supportsStepInTargetsRequest", false);
        capabilities.addProperty("supportsCompletionsRequest", false);
        capabilities.addProperty("supportsModulesRequest", false);
        capabilities.addProperty("supportsExceptionOptions", false);
        capabilities.addProperty("supportsValueFormattingOptions", false);
        capabilities.addProperty("supportsExceptionInfoRequest", false);
        capabilities.addProperty("supportTerminateDebuggee", true);
        capabilities.addProperty("supportsDelayedStackTraceLoading", false);
        capabilities.addProperty("supportsLoadedSourcesRequest", false);

        sendResponse(request, true, capabilities);
        sendEvent("initialized", null);
    }

    private void handleLaunch(JsonObject request, JsonObject args) {
        log.debug("handleLaunch received args: {}", args);

        // Parse launch arguments
        featurePath = args.has("feature") ? args.get("feature").getAsString() : null;

        // Validate feature path - must be a valid .feature file path
        if (featurePath == null || featurePath.isEmpty()) {
            JsonObject errorBody = new JsonObject();
            errorBody.addProperty("message", "No feature file specified");
            sendResponse(request, false, errorBody);
            return;
        }

        // Strip line number suffix if present (e.g., /path/to/file.feature:17)
        String pathToCheck = featurePath;
        if (pathToCheck.contains(":")) {
            int colonIdx = pathToCheck.lastIndexOf(':');
            String afterColon = pathToCheck.substring(colonIdx + 1);
            // Only strip if what's after the colon is a number (line number)
            if (afterColon.matches("\\d+")) {
                pathToCheck = pathToCheck.substring(0, colonIdx);
            }
        }

        // Check if it's a valid feature file path
        if (!pathToCheck.endsWith(".feature")) {
            JsonObject errorBody = new JsonObject();
            errorBody.addProperty("message", "Invalid feature file: " + featurePath +
                ". Please open a .feature file and try again.");
            sendResponse(request, false, errorBody);
            return;
        }

        // Check if the file exists
        java.io.File featureFile = new java.io.File(pathToCheck);
        if (!featureFile.exists()) {
            JsonObject errorBody = new JsonObject();
            errorBody.addProperty("message", "Feature file not found: " + pathToCheck);
            sendResponse(request, false, errorBody);
            return;
        }

        enableJavaDebugging = args.has("enableJavaDebugging") && args.get("enableJavaDebugging").getAsBoolean();
        enableJsDebugging = args.has("enableJsDebugging") && args.get("enableJsDebugging").getAsBoolean();

        // Step filtering options (default to true for backward compatibility)
        boolean skipJdkClasses = !args.has("skipJdkClasses") || args.get("skipJdkClasses").getAsBoolean();
        boolean skipKarateFramework = !args.has("skipKarateFramework") || args.get("skipKarateFramework").getAsBoolean();
        boolean skipKarateDependencies = !args.has("skipKarateDependencies") || args.get("skipKarateDependencies").getAsBoolean();

        log.info("Polyglot launch: feature={}, java={}, js={}, skipJdk={}, skipKarate={}, skipKarateDeps={}",
                featurePath, enableJavaDebugging, enableJsDebugging, skipJdkClasses, skipKarateFramework, skipKarateDependencies);

        // Create child process config
        config = new ChildProcessConfig()
            .workingDirectory(new File(workspaceRoot))
            .featurePath(featurePath)
            .classpath(classpath)
            .karateEnv(karateEnv)
            .logLevel(logLevel)
            .enableJavaDebugging(enableJavaDebugging)
            .enableJsDebugging(enableJsDebugging)
            .skipJdkClasses(skipJdkClasses)
            .skipKarateFramework(skipKarateFramework)
            .skipKarateDependencies(skipKarateDependencies)
            .sourcePaths(sourcePaths);

        // Create coordinator
        coordinator = new DebugCoordinator(config);
        coordinator.setEventListener(this);

        // Transfer any pre-launch breakpoints to the coordinator
        // (VS Code sends setBreakpoints before launch, so we queue them)
        synchronized (preLaunchBreakpoints) {
            if (!preLaunchBreakpoints.isEmpty()) {
                log.info("Transferring {} pre-launch breakpoint requests to coordinator", preLaunchBreakpoints.size());
                for (PreLaunchBreakpoints bp : preLaunchBreakpoints) {
                    coordinator.setBreakpoints(bp.filePath(), bp.requests());
                }
                preLaunchBreakpoints.clear();
            }
        }

        // Initialize asynchronously - store future so configurationDone can wait for it
        initializationFuture = coordinator.initialize()
            .thenRun(() -> {
                log.debug("Coordinator initialized successfully");
                sendResponse(request, true, null);
            })
            .exceptionally(e -> {
                log.error("Failed to initialize coordinator", e);
                JsonObject body = new JsonObject();
                body.addProperty("message", "Failed to initialize: " + e.getMessage());
                sendResponse(request, false, body);
                return null;
            });
    }

    private void handleSetBreakpoints(JsonObject request, JsonObject args) {
        String sourcePath = args.getAsJsonObject("source").get("path").getAsString();
        JsonArray breakpointsArray = args.has("breakpoints") ? args.getAsJsonArray("breakpoints") : new JsonArray();

        List<BreakpointRequest> requests = new ArrayList<>();
        for (int i = 0; i < breakpointsArray.size(); i++) {
            JsonObject bp = breakpointsArray.get(i).getAsJsonObject();
            int line = bp.get("line").getAsInt();
            String condition = bp.has("condition") ? bp.get("condition").getAsString() : null;
            String hitCondition = bp.has("hitCondition") ? bp.get("hitCondition").getAsString() : null;
            String logMessage = bp.has("logMessage") ? bp.get("logMessage").getAsString() : null;
            requests.add(new BreakpointRequest(line, condition, hitCondition, logMessage));
        }

        List<Breakpoint> breakpoints;

        // VS Code sends setBreakpoints after initialized event but before launch.
        // If coordinator doesn't exist yet, queue the breakpoints for later.
        if (coordinator == null) {
            log.debug("Pre-launch breakpoints received for {}, queuing {} breakpoints", sourcePath, requests.size());
            synchronized (preLaunchBreakpoints) {
                // Remove any existing breakpoints for this file (VS Code sends all breakpoints on each request)
                preLaunchBreakpoints.removeIf(bp -> bp.filePath().equals(sourcePath));
                preLaunchBreakpoints.add(new PreLaunchBreakpoints(sourcePath, new ArrayList<>(requests)));
            }
            // Return unverified breakpoints - they'll be verified when coordinator is ready
            breakpoints = new ArrayList<>();
            int id = 1;
            for (BreakpointRequest req : requests) {
                breakpoints.add(Breakpoint.unverified(id++, req.line(), sourcePath, "Pending - waiting for debug session"));
            }
        } else {
            breakpoints = coordinator.setBreakpoints(sourcePath, requests);
        }

        // Convert to DAP format
        JsonArray bpArray = new JsonArray();
        for (Breakpoint bp : breakpoints) {
            JsonObject bpObj = new JsonObject();
            bpObj.addProperty("id", bp.id());
            bpObj.addProperty("verified", bp.verified());
            bpObj.addProperty("line", bp.line());
            if (bp.message() != null) {
                bpObj.addProperty("message", bp.message());
            }
            bpArray.add(bpObj);
        }

        JsonObject body = new JsonObject();
        body.add("breakpoints", bpArray);
        sendResponse(request, true, body);
    }

    private void handleConfigurationDone(JsonObject request) {
        // Wait for initialization to complete before starting execution
        if (initializationFuture != null && !initializationFuture.isDone()) {
            log.trace("Waiting for coordinator initialization before starting...");
            initializationFuture.thenRun(() -> {
                sendResponse(request, true, null);
                coordinator.start();
            });
        } else {
            sendResponse(request, true, null);
            coordinator.start();
        }
    }

    private void handleThreads(JsonObject request) {
        JsonObject body = new JsonObject();
        JsonArray threads = new JsonArray();

        // Always include Karate main thread
        JsonObject karateThread = new JsonObject();
        karateThread.addProperty("id", 1);
        karateThread.addProperty("name", "Karate Main");
        threads.add(karateThread);

        // Include Java thread if we have a Java backend and it's stopped
        int stoppedThreadId = coordinator.getStoppedThreadId();
        log.trace("handleThreads: stoppedThreadId={}", stoppedThreadId);
        if (stoppedThreadId >= 2000 && stoppedThreadId < 3000) {
            // Java thread range
            JsonObject javaThread = new JsonObject();
            javaThread.addProperty("id", stoppedThreadId);
            javaThread.addProperty("name", "Java Thread");
            threads.add(javaThread);
        } else if (stoppedThreadId >= 1000 && stoppedThreadId < 2000) {
            // JavaScript thread range
            JsonObject jsThread = new JsonObject();
            jsThread.addProperty("id", stoppedThreadId);
            jsThread.addProperty("name", "JavaScript Thread");
            threads.add(jsThread);
        }

        body.add("threads", threads);
        sendResponse(request, true, body);
    }

    private void handleStackTrace(JsonObject request, JsonObject args) {
        int threadId = args.get("threadId").getAsInt();
        log.trace("handleStackTrace: threadId={}", threadId);
        List<StackFrame> frames = coordinator.getStackFrames(threadId);

        JsonArray framesArray = new JsonArray();
        for (StackFrame frame : frames) {
            JsonObject frameObj = new JsonObject();
            frameObj.addProperty("id", frame.id());
            frameObj.addProperty("name", frame.name());

            JsonObject source = new JsonObject();
            String sourcePath = frame.sourcePath();

            // Log source path details for debugging inline values support
            boolean isLocal = sourcePath != null && isLocalFile(sourcePath);
            log.trace("Frame source: path={}, isLocal={}", sourcePath, isLocal);

            // If source doesn't exist locally, try to extract from configured source paths
            // This enables VS Code's inline variable display by providing a real file path
            if (sourcePath != null && !isLocal) {
                String extractedPath = tryExtractSourceToTempFile(sourcePath);
                if (extractedPath != null) {
                    sourcePath = extractedPath;
                    isLocal = true;
                    log.trace("Using extracted source: {}", extractedPath);
                }
            }

            source.addProperty("path", sourcePath);
            source.addProperty("name", frame.sourceName());

            // For files that still don't exist locally, add a sourceReference so VS Code
            // will request the content via the "source" DAP request instead of
            // trying to open the file directly (which would fail)
            // NOTE: sourceReference prevents VS Code inline values from working
            if (sourcePath != null && !isLocal) {
                int sourceRef = getOrCreateSourceReference(sourcePath);
                source.addProperty("sourceReference", sourceRef);
                log.trace("Added sourceReference {} for non-local source: {}", sourceRef, sourcePath);
            }

            frameObj.add("source", source);

            frameObj.addProperty("line", frame.line());
            frameObj.addProperty("column", frame.column());
            framesArray.add(frameObj);
        }

        JsonObject body = new JsonObject();
        body.add("stackFrames", framesArray);
        body.addProperty("totalFrames", frames.size());

        // Log first few frames with their IDs to verify global mapping
        if (!frames.isEmpty()) {
            log.trace("Sending stackTrace response: {} frames, first frame id={}, name={}, line={}",
                frames.size(), frames.get(0).id(), frames.get(0).name(), frames.get(0).line());
        }

        sendResponse(request, true, body);
    }

    /**
     * Check if a source path points to a local file that exists on disk.
     */
    private boolean isLocalFile(String sourcePath) {
        if (sourcePath == null) return false;

        // If it's an absolute path, check if file exists
        File file = new File(sourcePath);
        if (file.isAbsolute()) {
            return file.exists();
        }

        // For relative paths, try to resolve against workspace
        String wsRoot = coordinator != null ? coordinator.getWorkspaceRoot() : workspaceRoot;
        if (wsRoot != null) {
            String[] searchPaths = {
                sourcePath,
                "src/test/java/" + sourcePath,
                "src/test/resources/" + sourcePath,
                "src/main/java/" + sourcePath,
                "src/main/resources/" + sourcePath
            };

            for (String searchPath : searchPaths) {
                file = new File(wsRoot, searchPath);
                if (file.exists()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get or create a source reference ID for a source path.
     * Source references are used for files that don't exist locally (e.g., from JARs).
     */
    private int getOrCreateSourceReference(String sourcePath) {
        return pathToSourceRef.computeIfAbsent(sourcePath, path -> {
            int ref = nextSourceReference.getAndIncrement();
            sourceRefToPath.put(ref, path);
            log.trace("Created sourceReference {} for path: {}", ref, path);
            return ref;
        });
    }

    private void handleScopes(JsonObject request, JsonObject args) {
        int frameId = args.get("frameId").getAsInt();
        List<Scope> scopes = coordinator.getScopes(frameId);

        JsonArray scopesArray = new JsonArray();
        for (Scope scope : scopes) {
            JsonObject scopeObj = new JsonObject();
            scopeObj.addProperty("name", scope.name());
            scopeObj.addProperty("variablesReference", scope.variablesReference());
            scopeObj.addProperty("expensive", scope.expensive());
            scopesArray.add(scopeObj);
        }

        JsonObject body = new JsonObject();
        body.add("scopes", scopesArray);
        sendResponse(request, true, body);
    }

    private void handleVariables(JsonObject request, JsonObject args) {
        int variablesReference = args.get("variablesReference").getAsInt();
        List<Variable> variables = coordinator.getVariables(variablesReference);

        JsonArray variablesArray = new JsonArray();
        for (Variable variable : variables) {
            JsonObject varObj = new JsonObject();
            varObj.addProperty("name", variable.name());
            varObj.addProperty("value", variable.value());
            varObj.addProperty("type", variable.type());
            varObj.addProperty("variablesReference", variable.variablesReference());
            // evaluateName tells VS Code how to evaluate this variable for inline values display
            if (variable.evaluateName() != null) {
                varObj.addProperty("evaluateName", variable.evaluateName());
            }
            variablesArray.add(varObj);
        }

        JsonObject body = new JsonObject();
        body.add("variables", variablesArray);
        sendResponse(request, true, body);
    }

    private void handleSetVariable(JsonObject request, JsonObject args) {
        int variablesReference = args.get("variablesReference").getAsInt();
        String name = args.get("name").getAsString();
        String value = args.get("value").getAsString();

        try {
            SetVariableResult result = coordinator.setVariable(variablesReference, name, value);

            JsonObject body = new JsonObject();
            body.addProperty("value", result.value());
            body.addProperty("type", result.type());
            body.addProperty("variablesReference", result.variablesReference());
            sendResponse(request, true, body);
        } catch (Exception e) {
            log.error("setVariable failed: name='{}', value='{}'", name, value, e);
            JsonObject body = new JsonObject();
            body.addProperty("message", "Error: " + e.getMessage());
            sendResponse(request, false, body);
        }
    }

    private void handleEvaluate(JsonObject request, JsonObject args) {
        String expression = args.get("expression").getAsString();
        int frameId = args.has("frameId") ? args.get("frameId").getAsInt() : 0;
        String context = args.has("context") ? args.get("context").getAsString() : "repl";

        // Filter out expressions that look like package names or import statements
        // These are sent by VS Code's inline values feature when scanning source files
        // but aren't valid expressions to evaluate
        if (isPackageOrImportExpression(expression)) {
            log.trace("Skipping evaluation of package/import expression: {}", expression);
            JsonObject body = new JsonObject();
            body.addProperty("result", "");
            body.addProperty("variablesReference", 0);
            sendResponse(request, true, body);
            return;
        }

        try {
            EvaluateResult result = coordinator.evaluate(frameId, expression, context);

            JsonObject body = new JsonObject();
            body.addProperty("result", result.value());
            body.addProperty("type", result.type());
            body.addProperty("variablesReference", result.variablesReference());
            sendResponse(request, true, body);
        } catch (Exception e) {
            log.error("evaluate failed: expression='{}'", expression, e);
            JsonObject body = new JsonObject();
            body.addProperty("result", "Error: " + e.getMessage());
            body.addProperty("variablesReference", 0);
            sendResponse(request, true, body);
        }
    }

    // Common Java class names that appear in import statements
    // These are NOT evaluatable as runtime expressions
    private static final java.util.Set<String> COMMON_CLASS_NAMES = java.util.Set.of(
        // java.lang
        "Object", "String", "Class", "System", "Thread", "Throwable", "Exception",
        "RuntimeException", "Error", "Integer", "Long", "Double", "Float", "Boolean",
        "Byte", "Short", "Character", "Number", "Math", "StringBuilder", "StringBuffer",
        "Void", "Enum", "Comparable", "Cloneable", "Runnable", "AutoCloseable",
        // java.lang.reflect
        "Method", "Field", "Constructor", "Modifier", "Array", "Proxy",
        // java.lang.invoke
        "MethodHandle", "MethodHandles", "MethodType", "VarHandle", "CallSite",
        // java.util
        "List", "Map", "Set", "Collection", "Iterator", "Iterable", "Optional",
        "ArrayList", "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet",
        "Collections", "Arrays", "Objects", "Comparator", "Random", "UUID",
        // java.util.concurrent
        "ConcurrentHashMap", "ConcurrentMap", "Executor", "ExecutorService",
        "Future", "CompletableFuture", "Callable", "Lock", "ReentrantLock",
        // java.io
        "File", "InputStream", "OutputStream", "Reader", "Writer", "Serializable",
        // java.nio
        "Path", "Paths", "Files", "ByteBuffer", "Buffer",
        // java.net
        "URL", "URI", "Socket", "ServerSocket", "HttpURLConnection",
        // Common annotations
        "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface"
    );

    /**
     * Check if an expression looks like a Java package name, import statement,
     * or static class reference that cannot be evaluated as a runtime expression.
     * These should be silently ignored to avoid showing error messages inline.
     *
     * Examples that should return true (not evaluatable):
     * - "java.util.concurrent" (package name)
     * - "java.util.HashMap" (fully qualified class name)
     * - "java." (partial package name from import line)
     * - "Class.getPrimitiveClass" (static method reference)
     * - "Boolean.TYPE" (static field reference)
     * - "Method" (class name from import statement)
     *
     * Examples that should return false (potentially evaluatable):
     * - "myVariable" (local variable)
     * - "this.field" (field access)
     * - "array[0]" (array access)
     * - "obj.method()" (method call)
     * - "result.value" (instance field access)
     */
    private boolean isPackageOrImportExpression(String expression) {
        if (expression == null || expression.isEmpty()) {
            return false;
        }

        // If it ends with a dot, it's a partial expression (like "java." from imports)
        if (expression.endsWith(".")) {
            return true;
        }

        // Check for common class names (single words from import statements)
        if (COMMON_CLASS_NAMES.contains(expression)) {
            return true;
        }

        // Single words that don't contain dots
        if (!expression.contains(".")) {
            // If it's a single uppercase word that looks like a class name
            // and is not a known variable pattern, skip it
            // (Variables typically start with lowercase)
            if (!expression.isEmpty() && Character.isUpperCase(expression.charAt(0))) {
                // Check if it looks like a type name (all letters, starts with uppercase)
                boolean looksLikeClassName = true;
                for (int i = 0; i < expression.length(); i++) {
                    if (!Character.isJavaIdentifierPart(expression.charAt(i))) {
                        looksLikeClassName = false;
                        break;
                    }
                }
                // Be conservative: only skip if it looks exactly like a class name
                // and is at least 2 chars (to avoid single-letter generics like T, K, V)
                if (looksLikeClassName && expression.length() >= 2) {
                    // Additional heuristic: skip if second char is also uppercase (like "Method", "HashMap")
                    // or if it ends with common class suffixes
                    if (expression.length() >= 2 && Character.isUpperCase(expression.charAt(1))) {
                        return true;
                    }
                    if (expression.endsWith("Exception") || expression.endsWith("Error") ||
                        expression.endsWith("Handler") || expression.endsWith("Factory") ||
                        expression.endsWith("Service") || expression.endsWith("Manager") ||
                        expression.endsWith("Builder") || expression.endsWith("Listener") ||
                        expression.endsWith("Adapter") || expression.endsWith("Provider") ||
                        expression.endsWith("Impl") || expression.endsWith("Helper") ||
                        expression.endsWith("Utils") || expression.endsWith("Util")) {
                        return true;
                    }
                }
            }
            return false;
        }

        // If it contains operators, parentheses, brackets, or special chars,
        // it's an actual expression that should be evaluated
        if (expression.contains("(") || expression.contains(")") ||
            expression.contains("[") || expression.contains("]") ||
            expression.contains(" ") || expression.contains("=") ||
            expression.contains("+") || expression.contains("-") ||
            expression.contains("*") || expression.contains("/") ||
            expression.contains("<") || expression.contains(">")) {
            return false;
        }

        String[] parts = expression.split("\\.");
        if (parts.length < 2) {
            return false;
        }

        String firstPart = parts[0];

        // Common Java package prefixes - definitely not evaluatable
        if (firstPart.equals("java") || firstPart.equals("javax") ||
            firstPart.equals("jdk") || firstPart.equals("sun") ||
            firstPart.equals("com") || firstPart.equals("org") ||
            firstPart.equals("net") || firstPart.equals("io")) {
            return true;
        }

        // If the first part starts with uppercase, it's likely a class name
        // (e.g., "Class.getPrimitiveClass", "Boolean.TYPE", "System.out")
        // These are static references that our simple evaluator can't handle
        if (!firstPart.isEmpty() && Character.isUpperCase(firstPart.charAt(0))) {
            return true;
        }

        // If it has 3+ parts and all parts are valid identifiers, likely a package name
        if (parts.length >= 3) {
            for (String part : parts) {
                if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                    return false;
                }
                for (int i = 1; i < part.length(); i++) {
                    if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                        return false;
                    }
                }
            }
            return true;
        }

        return false;
    }

    private void handleSource(JsonObject request, JsonObject args) {
        // VS Code is asking for source content - either from file system or decompiled
        try {
            JsonObject source = args.getAsJsonObject("source");
            String sourcePath = source != null && source.has("path") ? source.get("path").getAsString() : null;

            // Check for sourceReference - this means VS Code is requesting content for a
            // file that doesn't exist locally (e.g., from a JAR or needs decompilation)
            int sourceReference = 0;
            if (source != null && source.has("sourceReference")) {
                sourceReference = source.get("sourceReference").getAsInt();
            } else if (args.has("sourceReference")) {
                sourceReference = args.get("sourceReference").getAsInt();
            }

            // If we have a sourceReference, look up the path
            if (sourceReference > 0) {
                String refPath = sourceRefToPath.get(sourceReference);
                if (refPath != null) {
                    log.trace("Source request with sourceReference={}, resolved to path: {}", sourceReference, refPath);
                    sourcePath = refPath;
                } else {
                    log.warn("Unknown sourceReference: {}", sourceReference);
                }
            }

            if (sourcePath != null) {
                log.trace("handleSource: looking for source path: {}", sourcePath);

                // First, try to find the source file on disk
                java.io.File file = new java.io.File(sourcePath);

                // If not absolute, try resolving against workspace and common source directories
                if (!file.isAbsolute() || !file.exists()) {
                    String wsRoot = coordinator != null ? coordinator.getWorkspaceRoot() : workspaceRoot;
                    if (wsRoot != null) {
                        String[] searchPaths = {
                            sourcePath,
                            "src/test/java/" + sourcePath,
                            "src/test/resources/" + sourcePath,
                            "src/main/java/" + sourcePath,
                            "src/main/resources/" + sourcePath
                        };

                        for (String searchPath : searchPaths) {
                            file = new java.io.File(wsRoot, searchPath);
                            if (file.exists()) {
                                break;
                            }
                        }
                    }
                }

                // If file exists, return its contents
                if (file.exists()) {
                    String content = java.nio.file.Files.readString(file.toPath());
                    JsonObject body = new JsonObject();
                    body.addProperty("content", content);
                    sendResponse(request, true, body);
                    return;
                }

                // Try to find source in additional source paths (including zip files like src.zip)
                String sourceContent = tryFindSourceInAdditionalPaths(sourcePath);
                if (sourceContent != null) {
                    JsonObject body = new JsonObject();
                    body.addProperty("content", sourceContent);
                    sendResponse(request, true, body);
                    return;
                }

                // File not found - try decompilation if it looks like a class path
                if (DecompilationService.isDecompilableSourcePath(sourcePath)) {
                    log.trace("Attempting to decompile/load source for: {}", sourcePath);
                    String decompiled = tryDecompile(sourcePath);
                    if (decompiled != null) {
                        log.debug("Successfully loaded source for: {}", sourcePath);
                        JsonObject body = new JsonObject();
                        body.addProperty("content", decompiled);
                        sendResponse(request, true, body);
                        return;
                    }
                }

                log.warn("Source file not found: {} (tried workspace: {})", sourcePath, workspaceRoot);
            }

            // If we can't find the file and can't decompile, send an error
            sendResponse(request, false, null);
        } catch (Exception e) {
            log.error("Error reading source file", e);
            sendResponse(request, false, null);
        }
    }

    /**
     * Attempts to decompile a class file from the classpath.
     * Lazily initializes the decompilation service on first use.
     */
    private String tryDecompile(String sourcePath) {
        // Initialize decompilation service lazily (needs classpath from Java backend)
        if (decompilationService == null && coordinator != null) {
            List<String> classpathEntries = coordinator.getJavaClasspathEntries();
            if (!classpathEntries.isEmpty()) {
                decompilationService = new DecompilationService(classpathEntries);
            }
        }

        if (decompilationService == null) {
            log.trace("Decompilation service not available (no classpath entries)");
            return null;
        }

        return decompilationService.getSourceByPath(sourcePath);
    }

    /**
     * Try to find source content in the additional source paths configured for the debug session.
     * This includes directories and zip/jar archives (like JDK's src.zip).
     *
     * @param relativePath The relative path to the source file (e.g., "java/lang/String.java")
     * @return The source content if found, null otherwise
     */
    private String tryFindSourceInAdditionalPaths(String relativePath) {
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            return null;
        }

        // Parse source paths (semicolon-separated)
        String[] paths = sourcePaths.split(";");
        for (String sourcePath : paths) {
            if (sourcePath.isEmpty()) {
                continue;
            }

            java.io.File sourcePathFile = new java.io.File(sourcePath);
            if (!sourcePathFile.exists()) {
                continue;
            }

            // Check if it's a zip/jar file
            if (sourcePath.endsWith(".zip") || sourcePath.endsWith(".jar")) {
                String content = readSourceFromZip(sourcePathFile, relativePath);
                if (content != null) {
                    log.trace("Found source in archive: {} -> {}", sourcePath, relativePath);
                    return content;
                }
            } else if (sourcePathFile.isDirectory()) {
                // It's a directory - check if file exists
                java.io.File sourceFile = new java.io.File(sourcePathFile, relativePath);
                if (sourceFile.exists()) {
                    try {
                        log.trace("Found source in directory: {}", sourceFile.getAbsolutePath());
                        return java.nio.file.Files.readString(sourceFile.toPath());
                    } catch (java.io.IOException e) {
                        log.warn("Failed to read source file: {}", sourceFile.getAbsolutePath(), e);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Read source content from a zip/jar archive.
     *
     * @param zipFile The zip/jar file to search
     * @param relativePath The relative path to the source file within the archive
     * @return The source content if found, null otherwise
     */
    private String readSourceFromZip(java.io.File zipFile, String relativePath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            // Try exact path first
            java.util.zip.ZipEntry entry = zip.getEntry(relativePath);

            // If not found and path doesn't start with package dir, try common patterns
            if (entry == null && !relativePath.startsWith("/")) {
                // Some archives have sources without leading slash
                entry = zip.getEntry("/" + relativePath);
            }

            if (entry != null) {
                try (java.io.InputStream is = zip.getInputStream(entry)) {
                    return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (java.io.IOException e) {
            log.trace("Failed to read from zip archive: {}", zipFile.getAbsolutePath(), e);
        }

        return null;
    }

    /**
     * Try to extract a source file from configured source paths to a temp file.
     * This enables VS Code's inline variable display by providing a real file path
     * instead of using sourceReference (which doesn't support inline values).
     *
     * @param relativePath The relative path to the source file (e.g., "java/util/HashMap.java")
     * @return The absolute path to the extracted temp file, or null if not found
     */
    private String tryExtractSourceToTempFile(String relativePath) {
        if (sourcePaths == null || sourcePaths.isEmpty() || relativePath == null) {
            return null;
        }

        // Parse source paths (semicolon-separated)
        String[] paths = sourcePaths.split(";");
        for (String sourcePath : paths) {
            if (sourcePath.isEmpty()) {
                continue;
            }

            java.io.File sourcePathFile = new java.io.File(sourcePath);
            if (!sourcePathFile.exists()) {
                continue;
            }

            // Check if it's a zip/jar file
            if (sourcePath.endsWith(".zip") || sourcePath.endsWith(".jar")) {
                String extractedPath = extractFromZipToTempFile(sourcePathFile, relativePath);
                if (extractedPath != null) {
                    return extractedPath;
                }
            } else if (sourcePathFile.isDirectory()) {
                // It's a directory - check if file exists
                java.io.File sourceFile = new java.io.File(sourcePathFile, relativePath);
                if (sourceFile.exists()) {
                    return sourceFile.getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * Extract a source file from a zip archive to a temp file.
     * Uses caching to avoid repeated extractions.
     * Handles JDK src.zip module prefixes (java.base/, etc.).
     *
     * @param zipFile The zip/jar file to search
     * @param relativePath The relative path to the source file within the archive
     * @return The absolute path to the extracted temp file, or null if not found
     */
    private String extractFromZipToTempFile(java.io.File zipFile, String relativePath) {
        String cacheKey = zipFile.getAbsolutePath() + "!" + relativePath;

        // Check cache first
        String cached = extractedSourceCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            // Try exact path first
            java.util.zip.ZipEntry entry = zip.getEntry(relativePath);

            // If not found, try with JDK module prefixes (for JDK 9+ src.zip)
            if (entry == null) {
                String[] modulePrefixes = {
                    "java.base/",
                    "java.logging/",
                    "java.sql/",
                    "java.xml/",
                    "java.naming/",
                    "java.desktop/",
                    "java.net.http/",
                    "java.compiler/",
                    "java.management/",
                    "jdk.internal.vm.ci/"
                };

                for (String prefix : modulePrefixes) {
                    entry = zip.getEntry(prefix + relativePath);
                    if (entry != null) {
                        break;
                    }
                }
            }

            if (entry == null) {
                return null;
            }

            // Create temp directory if needed
            if (tempSourceDir == null) {
                tempSourceDir = java.nio.file.Files.createTempDirectory("karate-debug-sources").toFile();
                tempSourceDir.deleteOnExit();
                log.debug("Created temp source directory: {}", tempSourceDir);
            }

            // Create the extracted file path, preserving package structure
            java.io.File extractedFile = new java.io.File(tempSourceDir, relativePath);
            extractedFile.getParentFile().mkdirs();

            // Extract the source file
            try (java.io.InputStream is = zip.getInputStream(entry);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(extractedFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }

            String extractedPath = extractedFile.getAbsolutePath();
            extractedSourceCache.put(cacheKey, extractedPath);
            log.trace("Extracted source from {}: {} -> {}", zipFile.getName(), relativePath, extractedPath);
            return extractedPath;

        } catch (java.io.IOException e) {
            log.trace("Failed to extract {} from {}: {}", relativePath, zipFile.getName(), e.getMessage());
            return null;
        }
    }

    private void handleContinue(JsonObject request, JsonObject args) {
        int threadId = args.get("threadId").getAsInt();
        coordinator.resume(threadId);

        JsonObject body = new JsonObject();
        body.addProperty("allThreadsContinued", true);
        sendResponse(request, true, body);
    }

    private void handleNext(JsonObject request, JsonObject args) {
        int threadId = args.get("threadId").getAsInt();
        coordinator.stepOver(threadId);
        sendResponse(request, true, null);
    }

    private void handleStepIn(JsonObject request, JsonObject args) {
        int threadId = args.get("threadId").getAsInt();
        coordinator.stepInto(threadId);
        sendResponse(request, true, null);
    }

    private void handleStepOut(JsonObject request, JsonObject args) {
        int threadId = args.get("threadId").getAsInt();
        coordinator.stepOut(threadId);
        sendResponse(request, true, null);
    }

    private void handleDisconnect(JsonObject request) {
        if (coordinator != null) {
            coordinator.stop();
        }
        sendResponse(request, true, null);
        running = false;
    }

    private void cleanup() {
        running = false;

        if (coordinator != null) {
            try {
                coordinator.stop();
            } catch (Exception e) {
                log.error("Error stopping coordinator", e);
            }
        }

        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            log.error("Error closing socket", e);
        }
    }

    // MultiplexerEventListener implementation

    @Override
    public void onStopped(int globalThreadId, String reason, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason);
        body.addProperty("threadId", globalThreadId);
        if (description != null) {
            body.addProperty("description", description);
            body.addProperty("text", description);
        }
        body.addProperty("allThreadsStopped", true);
        log.trace("Sending stopped event to VS Code: threadId={}, reason={}", globalThreadId, reason);
        sendEvent("stopped", body);
    }

    @Override
    public void onContinued(int globalThreadId, boolean allThreadsContinued) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", globalThreadId);
        body.addProperty("allThreadsContinued", allThreadsContinued);
        sendEvent("continued", body);
    }

    @Override
    public void onTerminated() {
        sendEvent("terminated", null);
        running = false;
    }

    @Override
    public void onOutput(String category, String text) {
        // Output goes to stdout only (captured by VS Code for Output tab)
        // We don't send DAP output events to avoid duplicating in Debug Console
        System.out.print(text);
    }

    /**
     * Send output to stdout (captured by VS Code for Output tab).
     * We don't send DAP output events to keep Debug Console clean.
     * @param category "stdout", "stderr", or "console" (ignored)
     * @param text The output text
     */
    @Override
    public void sendOutputEvent(String category, String text) {
        // Output goes to stdout only (captured by VS Code for Output tab)
        System.out.println(text);
    }

    @Override
    public void onBreakpointResolved(Breakpoint breakpoint) {
        JsonObject body = new JsonObject();
        // DAP spec requires a 'reason' field: 'changed', 'new', or 'removed'
        body.addProperty("reason", "changed");
        JsonObject bp = new JsonObject();
        bp.addProperty("id", breakpoint.id());
        bp.addProperty("verified", breakpoint.verified());
        bp.addProperty("line", breakpoint.line());
        if (breakpoint.message() != null) {
            bp.addProperty("message", breakpoint.message());
        }
        body.add("breakpoint", bp);
        log.debug("Sending breakpoint event: id={}, verified={}, line={}",
                breakpoint.id(), breakpoint.verified(), breakpoint.line());
        sendEvent("breakpoint", body);
    }
}

