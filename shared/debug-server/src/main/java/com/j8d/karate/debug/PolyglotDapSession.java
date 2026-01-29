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
public class PolyglotDapSession implements MultiplexerEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolyglotDapSession.class);
    private static final String CONTENT_LENGTH = "Content-Length: ";

    private final Socket socket;
    private final String workspaceRoot;
    private final String karateEnv;
    private final String classpath;
    private final Gson gson;
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);

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

    // Decompilation support for viewing framework source code
    private DecompilationService decompilationService;

    // Source reference tracking for external sources (JARs, decompiled classes)
    // When a source file doesn't exist locally, we assign it a sourceReference ID
    // so VS Code will request the content via the "source" DAP request
    private final AtomicInteger nextSourceReference = new AtomicInteger(1);
    private final Map<Integer, String> sourceRefToPath = new ConcurrentHashMap<>();
    private final Map<String, Integer> pathToSourceRef = new ConcurrentHashMap<>();

    public PolyglotDapSession(Socket socket, String workspaceRoot, String karateEnv, String classpath) {
        this.socket = socket;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
        this.classpath = classpath;
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

    public synchronized void sendMessage(JsonObject message) {
        try {
            String json = gson.toJson(message);
            String header = CONTENT_LENGTH + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n";

            log.trace("Sending: {}", json);
            writer.write(header.getBytes(StandardCharsets.UTF_8));
            writer.write(json.getBytes(StandardCharsets.UTF_8));
            writer.flush();
        } catch (IOException e) {
            log.error("Error sending message", e);
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
        // Parse launch arguments
        featurePath = args.has("feature") ? args.get("feature").getAsString() : null;
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
            .enableJavaDebugging(enableJavaDebugging)
            .enableJsDebugging(enableJsDebugging)
            .skipJdkClasses(skipJdkClasses)
            .skipKarateFramework(skipKarateFramework)
            .skipKarateDependencies(skipKarateDependencies);

        // Create coordinator
        coordinator = new DebugCoordinator(config);
        coordinator.setEventListener(this);

        // Initialize asynchronously - store future so configurationDone can wait for it
        initializationFuture = coordinator.initialize()
            .thenRun(() -> {
                log.info("Coordinator initialized successfully");
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

        List<Breakpoint> breakpoints = coordinator.setBreakpoints(sourcePath, requests);

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
            log.debug("Waiting for coordinator initialization before starting...");
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
        log.debug("handleThreads: stoppedThreadId={}", stoppedThreadId);
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
        log.debug("handleStackTrace: threadId={}", threadId);
        List<StackFrame> frames = coordinator.getStackFrames(threadId);

        JsonArray framesArray = new JsonArray();
        for (StackFrame frame : frames) {
            JsonObject frameObj = new JsonObject();
            frameObj.addProperty("id", frame.id());
            frameObj.addProperty("name", frame.name());

            JsonObject source = new JsonObject();
            String sourcePath = frame.sourcePath();
            source.addProperty("path", sourcePath);
            source.addProperty("name", frame.sourceName());

            // For files that don't exist locally, add a sourceReference so VS Code
            // will request the content via the "source" DAP request instead of
            // trying to open the file directly (which would fail)
            if (sourcePath != null && !isLocalFile(sourcePath)) {
                int sourceRef = getOrCreateSourceReference(sourcePath);
                source.addProperty("sourceReference", sourceRef);
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
            log.debug("Sending stackTrace response: {} frames, first frame id={}, name={}, line={}",
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
            log.debug("Created sourceReference {} for path: {}", ref, path);
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
                    log.debug("Source request with sourceReference={}, resolved to path: {}", sourceReference, refPath);
                    sourcePath = refPath;
                } else {
                    log.warn("Unknown sourceReference: {}", sourceReference);
                }
            }

            if (sourcePath != null) {
                log.debug("handleSource: looking for source path: {}", sourcePath);

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

                // File not found - try decompilation if it looks like a class path
                if (DecompilationService.isDecompilableSourcePath(sourcePath)) {
                    log.debug("Attempting to decompile/load source for: {}", sourcePath);
                    String decompiled = tryDecompile(sourcePath);
                    if (decompiled != null) {
                        log.info("Successfully loaded source for: {}", sourcePath);
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
            log.debug("Decompilation service not available (no classpath entries)");
            return null;
        }

        return decompilationService.getSourceByPath(sourcePath);
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
        coordinator.stop();
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
        log.info("Sending stopped event to VS Code: threadId={}, reason={}", globalThreadId, reason);
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
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("output", text);
        sendEvent("output", body);
    }

    @Override
    public void onBreakpointResolved(Breakpoint breakpoint) {
        JsonObject body = new JsonObject();
        JsonObject bp = new JsonObject();
        bp.addProperty("id", breakpoint.id());
        bp.addProperty("verified", breakpoint.verified());
        bp.addProperty("line", breakpoint.line());
        if (breakpoint.message() != null) {
            bp.addProperty("message", breakpoint.message());
        }
        body.add("breakpoint", bp);
        sendEvent("breakpoint", body);
    }
}

