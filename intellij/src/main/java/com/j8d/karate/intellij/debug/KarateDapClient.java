package com.j8d.karate.intellij.debug;

import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.j8d.karate.intellij.project.KarateProjectService;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import com.j8d.karate.intellij.run.KarateRunConfiguration;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client for communicating with the Karate DAP (Debug Adapter Protocol) server.
 * Implements the DAP JSON protocol over socket.
 */
public class KarateDapClient {

    private static final Logger LOG = Logger.getInstance(KarateDapClient.class);
    private static final String CONTENT_LENGTH = "Content-Length: ";

    /** Breakpoint info including line and optional condition */
    public record BreakpointInfo(int line, String condition) {}

    private final KarateDebugProcess debugProcess;
    private final Gson gson = new GsonBuilder().create();
    private final AtomicInteger requestSeq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, BreakpointInfo>> breakpointsByFile = new ConcurrentHashMap<>();

    private Process serverProcess;
    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private int currentThreadId = 1;
    private final List<Runnable> pendingBreakpointActions = new ArrayList<>();

    public KarateDapClient(KarateDebugProcess debugProcess) {
        this.debugProcess = debugProcess;
    }

    public void start(KarateRunConfiguration configuration) throws Exception {
        // Find the debug server JAR
        String jarPath = findDebugServerJar();
        if (jarPath == null) {
            throw new Exception("Could not find karate-debug-server-1.0.0.jar");
        }

        debugProcess.log("Using debug server: " + jarPath);

        // Find a free port
        int port = findFreePort();
        debugProcess.log("Starting DAP server on port " + port);

        // Build classpath
        String classpath = buildClasspath(jarPath, configuration);

        // Get Java path
        String javaPath = findJavaPath();

        // Get workspace root
        String workspaceRoot = getWorkspaceRoot(configuration);

        // Get settings from project
        KarateProjectSettings settings = KarateProjectSettings.getInstance(
            debugProcess.getSession().getProject());

        // Environment: use config override, or fall back to settings
        String karateEnv = configuration.getKarateEnv();
        if (karateEnv == null || karateEnv.isEmpty()) {
            karateEnv = settings.getEffectiveEnvironment();
        }

        // Log level from settings
        String logLevel = settings.getEffectiveLogLevel();

        // Start the debug server
        List<String> command = new ArrayList<>();
        command.add(javaPath);
        command.add("-cp");
        command.add(classpath);
        command.add("com.j8d.karate.debug.DebugServer");
        command.add("-p");
        command.add(String.valueOf(port));
        command.add("-w");
        command.add(workspaceRoot);
        command.add("-e");
        command.add(karateEnv);
        command.add("-l");
        command.add(logLevel);

        debugProcess.log("Environment: " + karateEnv);
        debugProcess.log("Log level: " + logLevel);
        debugProcess.log("Command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workspaceRoot));
        pb.redirectErrorStream(true);
        serverProcess = pb.start();

        // Log server stdout in background (print statements, HTTP traffic, etc.)
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    debugProcess.log(line);
                }
            } catch (IOException e) {
                // Server closed
            }
        }, "DAP-Server-Output").start();

        // Wait for server to start
        waitForServer(port, 30000);

        // Connect to the server
        socket = new Socket("localhost", port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = socket.getOutputStream();

        running.set(true);
        debugProcess.log("Connected to DAP server");

        // Start message reading loop
        startMessageLoop();

        // Send DAP initialize sequence
        sendInitialize();
    }

    private void waitForServer(int port, int timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        debugProcess.log("Waiting for debug server on port " + port + "...");

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try (Socket test = new Socket("localhost", port)) {
                debugProcess.log("Debug server ready");
                return;
            } catch (IOException e) {
                Thread.sleep(200);
            }
        }
        throw new Exception("Timeout waiting for debug server to start");
    }

    private String buildClasspath(String jarPath, KarateRunConfiguration configuration) {
        KarateProjectService projectService = KarateProjectService.getInstance(
            debugProcess.getSession().getProject());

        // Debug server JAR should be first, then project classpath
        String projectClasspath = projectService.getClasspath();
        if (projectClasspath != null && !projectClasspath.isEmpty()) {
            return jarPath + File.pathSeparator + projectClasspath;
        }
        return jarPath;
    }

    private String findJavaPath() {
        // Try JAVA_HOME first
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path javaPath = Path.of(javaHome, "bin", "java");
            if (Files.exists(javaPath)) {
                return javaPath.toString();
            }
        }
        // Fallback to PATH
        return "java";
    }

    private String getWorkspaceRoot(KarateRunConfiguration configuration) {
        String featureFile = configuration.getFeatureFile();
        if (featureFile != null) {
            // Walk up to find project root (pom.xml or build.gradle)
            Path path = Path.of(featureFile).getParent();
            while (path != null) {
                if (Files.exists(path.resolve("pom.xml")) ||
                    Files.exists(path.resolve("build.gradle"))) {
                    return path.toString();
                }
                path = path.getParent();
            }
        }
        return debugProcess.getSession().getProject().getBasePath();
    }

    private String findDebugServerJar() {
        // Try plugin resources first
        Path pluginPath = Path.of(PathManager.getPluginsPath(),
            "karate-debug-intellij", "lib", "karate-debug-server-1.0.0.jar");
        if (Files.exists(pluginPath)) {
            return pluginPath.toString();
        }

        // Try development path (resources in plugin)
        Path resourcePath = Path.of(PathManager.getPluginsPath())
            .getParent().resolve("intellij/resources/karate-debug-server-1.0.0.jar");
        if (Files.exists(resourcePath)) {
            return resourcePath.toString();
        }

        // Try monorepo shared path
        Path sharedPath = Path.of(System.getProperty("user.dir"),
            "shared", "debug-server", "target", "karate-debug-server-1.0.0.jar");
        if (Files.exists(sharedPath)) {
            return sharedPath.toAbsolutePath().normalize().toString();
        }

        // Try parent directory
        Path parentPath = Path.of(System.getProperty("user.dir"),
            "..", "shared", "debug-server", "target", "karate-debug-server-1.0.0.jar");
        if (Files.exists(parentPath)) {
            return parentPath.toAbsolutePath().normalize().toString();
        }

        return null;
    }

    private int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ========== DAP Protocol Methods ==========

    private void sendInitialize() {
        JsonObject args = new JsonObject();
        args.addProperty("clientID", "intellij-karate");
        args.addProperty("clientName", "IntelliJ Karate Debug");
        args.addProperty("adapterID", "karate");
        args.addProperty("linesStartAt1", true);
        args.addProperty("columnsStartAt1", true);
        args.addProperty("supportsVariableType", true);
        args.addProperty("supportsVariablePaging", false);
        args.addProperty("supportsRunInTerminalRequest", false);

        sendRequest("initialize", args).thenAccept(response -> {
            debugProcess.log("Initialize response received");
            // Mark as initialized so breakpoints can be sent
            initialized.set(true);

            // Send any breakpoints that were queued before connection
            sendPendingBreakpoints();

            // Send initialized event acknowledgment
            sendRequest("initialized", null);
            // Now launch
            sendLaunch();
        });
    }

    private void sendLaunch() {
        KarateRunConfiguration config = debugProcess.getConfiguration();

        // Build feature path with optional line number suffix (like VS Code does)
        // Line number > 0 means run specific scenario at that line
        // Line number <= 0 means run entire feature
        String featurePath = config.getFeatureFile();
        int scenarioLine = config.getScenarioLine();
        if (scenarioLine > 0) {
            featurePath = featurePath + ":" + scenarioLine;
        }

        JsonObject args = new JsonObject();
        args.addProperty("feature", featurePath);

        debugProcess.log("Launching with feature: " + featurePath);

        sendRequest("launch", args).thenAccept(response -> {
            debugProcess.log("Launch response received - debug session started");
            // Send configurationDone to start execution
            sendRequest("configurationDone", null).thenAccept(configDoneResponse -> {
                debugProcess.log("Configuration done - execution starting");
            });
        });
    }

    private CompletableFuture<JsonObject> sendRequest(String command, JsonObject arguments) {
        int seq = requestSeq.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(seq, future);

        JsonObject request = new JsonObject();
        request.addProperty("seq", seq);
        request.addProperty("type", "request");
        request.addProperty("command", command);
        if (arguments != null) {
            request.add("arguments", arguments);
        }

        try {
            sendMessage(request);
        } catch (IOException e) {
            future.completeExceptionally(e);
            pendingRequests.remove(seq);
        }

        return future;
    }

    private void sendMessage(JsonObject message) throws IOException {
        if (writer == null) {
            throw new IOException("Not connected to DAP server");
        }

        String json = gson.toJson(message);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = CONTENT_LENGTH + bytes.length + "\r\n\r\n";

        synchronized (writer) {
            writer.write(header.getBytes(StandardCharsets.UTF_8));
            writer.write(bytes);
            writer.flush();
        }

        LOG.debug("Sent DAP message: " + message.get("command"));
    }

    private void startMessageLoop() {
        Thread messageThread = new Thread(() -> {
            while (running.get()) {
                try {
                    JsonObject message = readMessage();
                    if (message == null) {
                        break;
                    }
                    handleMessage(message);
                } catch (Exception e) {
                    if (running.get()) {
                        LOG.warn("Error reading DAP message", e);
                        debugProcess.log("Error reading DAP message: " + e.getMessage());
                    }
                }
            }
        }, "DAP-Message-Reader");
        messageThread.setDaemon(true);
        messageThread.start();
    }

    private JsonObject readMessage() throws IOException {
        // Read headers
        int contentLength = -1;
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith(CONTENT_LENGTH)) {
                contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }

        if (line == null || contentLength < 0) {
            return null;
        }

        // Read content
        char[] buffer = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(buffer, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }

        String json = new String(buffer);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void handleMessage(JsonObject message) {
        String type = message.get("type").getAsString();

        switch (type) {
            case "response":
                handleResponse(message);
                break;
            case "event":
                handleEvent(message);
                break;
            default:
                LOG.debug("Unknown DAP message type: " + type);
        }
    }

    private void handleResponse(JsonObject response) {
        int requestSeq = response.get("request_seq").getAsInt();
        CompletableFuture<JsonObject> future = pendingRequests.remove(requestSeq);

        if (future != null) {
            boolean success = response.get("success").getAsBoolean();
            if (success) {
                future.complete(response.has("body") ? response.getAsJsonObject("body") : new JsonObject());
            } else {
                String message = response.has("message") ? response.get("message").getAsString() : "Unknown error";
                future.completeExceptionally(new Exception("DAP error: " + message));
            }
        }
    }

    private void handleEvent(JsonObject event) {
        String eventName = event.get("event").getAsString();
        JsonObject body = event.has("body") ? event.getAsJsonObject("body") : null;

        switch (eventName) {
            case "stopped":
                handleStoppedEvent(body);
                break;
            case "terminated":
                handleTerminatedEvent();
                break;
            case "output":
                handleOutputEvent(body);
                break;
            default:
                LOG.debug("Unhandled DAP event: " + eventName);
        }
    }

    private void handleStoppedEvent(JsonObject body) {
        String reason = body != null && body.has("reason") ? body.get("reason").getAsString() : "unknown";
        currentThreadId = body != null && body.has("threadId") ? body.get("threadId").getAsInt() : 1;

        // Request stack trace to update UI
        ApplicationManager.getApplication().invokeLater(() -> {
            debugProcess.onStopped(currentThreadId, reason);
        });
    }

    private void handleTerminatedEvent() {
        debugProcess.log("Debug session terminated");
        ApplicationManager.getApplication().invokeLater(() -> {
            debugProcess.getSession().stop();
        });
    }

    private void handleOutputEvent(JsonObject body) {
        if (body != null && body.has("output")) {
            String output = body.get("output").getAsString();
            debugProcess.log(output.trim());
        }
    }

    // ========== Public Control Methods ==========

    public void sendStepOver() {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", currentThreadId);
        sendRequest("next", args);
    }

    public void sendStepInto() {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", currentThreadId);
        sendRequest("stepIn", args);
    }

    public void sendStepOut() {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", currentThreadId);
        sendRequest("stepOut", args);
    }

    public void sendContinue() {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", currentThreadId);
        sendRequest("continue", args);
    }

    /**
     * Set a breakpoint with an optional condition.
     * @param filePath The file path
     * @param line The line number (1-based)
     * @param condition Optional condition expression (null for unconditional)
     */
    public void setBreakpoint(String filePath, int line, String condition) {
        breakpointsByFile.computeIfAbsent(filePath, k -> new ConcurrentHashMap<>())
            .put(line, new BreakpointInfo(line, condition));

        if (initialized.get()) {
            sendBreakpointsForFile(filePath);
        } else {
            // Queue for later when connection is established
            synchronized (pendingBreakpointActions) {
                pendingBreakpointActions.add(() -> sendBreakpointsForFile(filePath));
            }
        }
    }

    /**
     * Set a breakpoint without a condition.
     */
    public void setBreakpoint(String filePath, int line) {
        setBreakpoint(filePath, line, null);
    }

    public void removeBreakpoint(String filePath, int line) {
        Map<Integer, BreakpointInfo> breakpoints = breakpointsByFile.get(filePath);
        if (breakpoints != null) {
            breakpoints.remove(line);
            if (initialized.get()) {
                sendBreakpointsForFile(filePath);
            }
        }
    }

    private void sendPendingBreakpoints() {
        synchronized (pendingBreakpointActions) {
            for (Runnable action : pendingBreakpointActions) {
                action.run();
            }
            pendingBreakpointActions.clear();
        }
    }

    private void sendBreakpointsForFile(String filePath) {
        if (!initialized.get()) {
            return;
        }

        Map<Integer, BreakpointInfo> breakpointsMap = breakpointsByFile.getOrDefault(filePath, Collections.emptyMap());

        JsonObject args = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("path", filePath);
        args.add("source", source);

        JsonArray breakpoints = new JsonArray();
        for (BreakpointInfo info : breakpointsMap.values()) {
            JsonObject bp = new JsonObject();
            bp.addProperty("line", info.line());
            if (info.condition() != null && !info.condition().isEmpty()) {
                bp.addProperty("condition", info.condition());
            }
            breakpoints.add(bp);
        }
        args.add("breakpoints", breakpoints);

        sendRequest("setBreakpoints", args).thenAccept(response -> {
            debugProcess.log("Breakpoints set for: " + filePath);
        });
    }

    public CompletableFuture<JsonObject> getStackTrace() {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", currentThreadId);
        return sendRequest("stackTrace", args);
    }

    public CompletableFuture<JsonObject> getScopes(int frameId) {
        JsonObject args = new JsonObject();
        args.addProperty("frameId", frameId);
        return sendRequest("scopes", args);
    }

    public CompletableFuture<JsonObject> getVariables(int variablesReference) {
        JsonObject args = new JsonObject();
        args.addProperty("variablesReference", variablesReference);
        return sendRequest("variables", args);
    }

    /**
     * Evaluate an expression in the debug context.
     * For match expressions, prefix with "match " (e.g., "match response.name == 'pikachu'")
     * @param expression The expression to evaluate
     * @param context The evaluation context: "repl", "watch", or "hover"
     * @return A future containing the result with "result" and "type" properties
     */
    public CompletableFuture<JsonObject> evaluate(String expression, String context) {
        JsonObject args = new JsonObject();
        args.addProperty("expression", expression);
        args.addProperty("context", context);
        return sendRequest("evaluate", args);
    }

    /**
     * Evaluate a match expression (e.g., "response.name == 'pikachu'").
     * Automatically prefixes with "match ".
     * @param matchExpression The match expression without "match " prefix
     * @return A future containing the result
     */
    public CompletableFuture<JsonObject> evaluateMatch(String matchExpression) {
        return evaluate("match " + matchExpression, "repl");
    }

    /**
     * Set a variable value (hot-swap).
     * @param variablesReference The variables reference (scope ID)
     * @param name The variable name
     * @param value The new value as a string
     * @return A future containing the result with "value" and "type" properties
     */
    public CompletableFuture<JsonObject> setVariable(int variablesReference, String name, String value) {
        JsonObject args = new JsonObject();
        args.addProperty("variablesReference", variablesReference);
        args.addProperty("name", name);
        args.addProperty("value", value);
        return sendRequest("setVariable", args);
    }

    public void stop() {
        running.set(false);

        try {
            // Send disconnect request
            if (socket != null && socket.isConnected()) {
                sendRequest("disconnect", null);
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (socket != null) socket.close();
            if (serverProcess != null) serverProcess.destroyForcibly();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}
