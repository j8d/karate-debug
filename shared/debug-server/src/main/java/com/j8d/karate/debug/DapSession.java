package com.j8d.karate.debug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Handles a single DAP (Debug Adapter Protocol) session.
 * Implements the JSON-based message protocol per DAP specification.
 */
public class DapSession implements OutputEventSender {
    private static final Logger logger = LoggerFactory.getLogger(DapSession.class);
    private static final String CONTENT_LENGTH = "Content-Length: ";

    private final Socket socket;
    private final String workspaceRoot;
    private final String karateEnv;
    private final Gson gson;
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);

    private BufferedReader reader;
    private OutputStream writer;
    private volatile boolean running = false;

    private KarateDebugger debugger;

    public DapSession(Socket socket, String workspaceRoot, String karateEnv) {
        this.socket = socket;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Run the DAP session.
     * @return true if at least one valid DAP message was processed, false if connection closed immediately
     */
    public boolean run() {
        boolean hadValidMessage = false;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = socket.getOutputStream();
            running = true;

            debugger = new KarateDebugger(this, workspaceRoot, karateEnv);

            // Register debugger with log breakpoint appender
            LogBreakpointAppender.setDebugger(debugger);

            while (running) {
                JsonObject message = readMessage();
                if (message == null) {
                    logger.trace("No more messages, ending session");
                    break;
                }
                hadValidMessage = true;
                handleMessage(message);
            }
            logger.trace("Session loop ended, running={}, hadValidMessage={}", running, hadValidMessage);
        } catch (IOException e) {
            logger.error("Session error", e);
        } finally {
            cleanup();
        }
        return hadValidMessage;
    }

    private JsonObject readMessage() throws IOException {
        // Read headers until empty line
        int contentLength = -1;
        String line;

        logger.trace("Waiting for message...");
        while ((line = reader.readLine()) != null) {
            logger.trace("Header line: '{}'", line);
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith(CONTENT_LENGTH)) {
                contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }

        if (line == null) {
            logger.trace("Connection closed by client");
            return null;
        }

        if (contentLength < 0) {
            logger.warn("No Content-Length header found");
            return null;
        }

        // Read the JSON content
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
        logger.trace("Received: {}", json);
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
        logger.trace("Sending: {}", json);

        synchronized (this) {
            try {
                writer.write(header);
                writer.write(jsonBytes);
                writer.flush();
            } catch (IOException e) {
                logger.error("Error sending message", e);
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

    private void handleMessage(JsonObject message) {
        String type = message.get("type").getAsString();

        if ("request".equals(type)) {
            String command = message.get("command").getAsString();
            logger.trace("Handling command: {}", command);
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
                default -> {
                    logger.warn("Unknown command: {}", command);
                    sendResponse(message, true, null);
                }
            }
        }
    }

    private void handleInitialize(JsonObject request, JsonObject args) {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("supportsConfigurationDoneRequest", true);
        capabilities.addProperty("supportsFunctionBreakpoints", false);
        capabilities.addProperty("supportsConditionalBreakpoints", false);
        capabilities.addProperty("supportsEvaluateForHovers", true);
        capabilities.addProperty("supportsStepBack", false);
        capabilities.addProperty("supportsSetVariable", true);
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
        String feature = args.has("feature") ? args.get("feature").getAsString() : null;
        if (feature != null) {
            debugger.setFeaturePath(feature);
        }

        // Parse log breakpoints if provided
        logger.debug("Launch args: {}", args);
        if (args.has("logBreakpoints")) {
            logger.debug("logBreakpoints field found: {}", args.get("logBreakpoints"));
            if (args.get("logBreakpoints").isJsonArray()) {
                var logBreakpointsArray = args.getAsJsonArray("logBreakpoints");
                java.util.List<String> patterns = new java.util.ArrayList<>();
                for (var element : logBreakpointsArray) {
                    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        String pattern = element.getAsString().trim();
                        if (!pattern.isEmpty()) {
                            patterns.add(pattern);
                        }
                    }
                }
                debugger.setLogBreakpoints(patterns);
            } else {
                logger.warn("logBreakpoints is not a JSON array: {}", args.get("logBreakpoints"));
            }
        } else {
            logger.debug("No logBreakpoints in launch args");
        }

        sendResponse(request, true, null);
    }

    private void handleSetBreakpoints(JsonObject request, JsonObject args) {
        String sourcePath = args.getAsJsonObject("source").get("path").getAsString();
        var breakpointsArray = args.getAsJsonArray("breakpoints");

        var result = debugger.setBreakpoints(sourcePath, breakpointsArray);

        JsonObject body = new JsonObject();
        body.add("breakpoints", result);
        sendResponse(request, true, body);
    }

    private void handleConfigurationDone(JsonObject request) {
        sendResponse(request, true, null);
        // Start the Karate test execution
        // If JS debugging is enabled with Suspend=true, GraalVM will pause
        // when loading karate-config.js, waiting for the debugger to connect
        debugger.startExecution();
    }

    private void handleThreads(JsonObject request) {
        JsonObject body = new JsonObject();
        var threads = new com.google.gson.JsonArray();
        JsonObject thread = new JsonObject();
        thread.addProperty("id", 1);
        thread.addProperty("name", "Karate Main");
        threads.add(thread);
        body.add("threads", threads);
        sendResponse(request, true, body);
    }

    private void handleStackTrace(JsonObject request, JsonObject args) {
        var frames = debugger.getStackFrames();
        JsonObject body = new JsonObject();
        body.add("stackFrames", frames);
        body.addProperty("totalFrames", frames.size());
        sendResponse(request, true, body);
    }

    private void handleScopes(JsonObject request, JsonObject args) {
        int frameId = args.get("frameId").getAsInt();
        var scopes = debugger.getScopes(frameId);
        JsonObject body = new JsonObject();
        body.add("scopes", scopes);
        sendResponse(request, true, body);
    }

    private void handleVariables(JsonObject request, JsonObject args) {
        int variablesReference = args.get("variablesReference").getAsInt();
        var variables = debugger.getVariables(variablesReference);
        JsonObject body = new JsonObject();
        body.add("variables", variables);
        sendResponse(request, true, body);
    }

    private void handleSetVariable(JsonObject request, JsonObject args) {
        int variablesReference = args.get("variablesReference").getAsInt();
        String name = args.get("name").getAsString();
        String value = args.get("value").getAsString();

        logger.debug("setVariable request: name='{}', value='{}', ref={}", name, value, variablesReference);

        try {
            var result = debugger.setVariable(variablesReference, name, value);

            JsonObject body = new JsonObject();
            body.addProperty("value", result.displayValue());
            body.addProperty("type", result.type());
            body.addProperty("variablesReference", 0);
            sendResponse(request, true, body);

            logger.debug("setVariable success: name='{}' -> {}", name, result.displayValue());
        } catch (Exception e) {
            logger.error("setVariable failed: name='{}', value='{}'", name, value, e);
            JsonObject body = new JsonObject();
            body.addProperty("value", "Error: " + e.getMessage());
            body.addProperty("type", "error");
            body.addProperty("variablesReference", 0);
            sendResponse(request, false, body);
        }
    }

    private void handleEvaluate(JsonObject request, JsonObject args) {
        String expression = args.get("expression").getAsString();
        String context = args.has("context") ? args.get("context").getAsString() : "repl";

        var result = debugger.evaluate(expression, context);

        JsonObject body = new JsonObject();
        body.addProperty("result", result.value());
        body.addProperty("type", result.type());
        body.addProperty("variablesReference", 0);
        sendResponse(request, true, body);
    }

    private void handleSource(JsonObject request, JsonObject args) {
        // VS Code is asking for source content - read from the file system
        try {
            JsonObject source = args.getAsJsonObject("source");
            String sourcePath = source != null && source.has("path") ? source.get("path").getAsString() : null;

            if (sourcePath != null) {
                java.io.File file = new java.io.File(sourcePath);

                // If not absolute, try resolving against workspace and common source directories
                if (!file.isAbsolute() || !file.exists()) {
                    String workspaceRoot = debugger.getWorkspaceRoot();
                    String[] searchPaths = {
                        sourcePath,
                        "src/test/java/" + sourcePath,
                        "src/test/resources/" + sourcePath,
                        "src/main/java/" + sourcePath,
                        "src/main/resources/" + sourcePath
                    };

                    for (String searchPath : searchPaths) {
                        file = new java.io.File(workspaceRoot, searchPath);
                        if (file.exists()) {
                            break;
                        }
                    }
                }

                if (file.exists()) {
                    String content = java.nio.file.Files.readString(file.toPath());
                    JsonObject body = new JsonObject();
                    body.addProperty("content", content);
                    sendResponse(request, true, body);
                    return;
                }

                logger.warn("Source file not found: {} (tried workspace: {})", sourcePath, debugger.getWorkspaceRoot());
            }

            // If we can't find the file, send an error
            sendResponse(request, false, null);
        } catch (Exception e) {
            logger.error("Error reading source file", e);
            sendResponse(request, false, null);
        }
    }

    private void handleContinue(JsonObject request, JsonObject args) {
        debugger.continueExecution();
        JsonObject body = new JsonObject();
        body.addProperty("allThreadsContinued", true);
        sendResponse(request, true, body);
    }

    private void handleNext(JsonObject request, JsonObject args) {
        debugger.stepOver();
        sendResponse(request, true, null);
    }

    private void handleStepIn(JsonObject request, JsonObject args) {
        debugger.stepIn();
        sendResponse(request, true, null);
    }

    private void handleStepOut(JsonObject request, JsonObject args) {
        debugger.stepOut();
        sendResponse(request, true, null);
    }

    private void handleDisconnect(JsonObject request) {
        debugger.stop();
        sendResponse(request, true, null);
        running = false;
    }

    private void cleanup() {
        running = false;

        // Unregister debugger from log breakpoint appender
        LogBreakpointAppender.clearDebugger();

        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            logger.error("Error closing socket", e);
        }
    }
}
