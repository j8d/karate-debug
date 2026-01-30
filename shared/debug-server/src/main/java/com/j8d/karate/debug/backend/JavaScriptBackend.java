package com.j8d.karate.debug.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.j8d.karate.debug.dap.DapClient;
import com.j8d.karate.debug.dap.DapEventListener;

/**
 * DebugBackend implementation for JavaScript debugging via DAP (Debug Adapter Protocol).
 *
 * Connects to GraalVM's built-in DAP server to debug JavaScript code executed
 * within Karate tests (karate-config.js, JS functions, etc.).
 */
public class JavaScriptBackend implements DebugBackend, DapEventListener {

    private static final Logger log = LoggerFactory.getLogger(JavaScriptBackend.class);

    private final DapClient dapClient;
    private final int dapPort;

    private BackendEventListener listener;
    private volatile boolean ready = false;

    // Source tracking: sourceReference -> SourceInfo
    private final Map<Integer, SourceInfo> sources = new ConcurrentHashMap<>();
    // Reverse lookup: normalized path -> sourceReference
    private final Map<String, Integer> pathToSourceRef = new ConcurrentHashMap<>();

    // Breakpoint tracking: our breakpoint ID -> verified status
    private final Map<Integer, Boolean> breakpointVerified = new ConcurrentHashMap<>();
    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);

    // Current pause state
    private volatile JsonArray currentStackFrames;
    private volatile String currentPauseReason;
    private volatile int currentThreadId = 1;  // JavaScript is single-threaded

    // ID allocation for variable references
    private final AtomicInteger nextVarRef = new AtomicInteger(1);

    // Variable reference -> scope/variables reference from DAP
    private final Map<Integer, Integer> varRefToDapRef = new ConcurrentHashMap<>();

    // Script entry catching for cross-language step-into
    private volatile boolean scriptEntryCatchingEnabled = false;
    // Track pending step-into across languages
    private volatile boolean pendingStepIn = false;

    /**
     * Creates a JavaScriptBackend that will connect to the given DAP server port.
     *
     * @param dapPort The port for the GraalVM DAP server
     */
    public JavaScriptBackend(int dapPort) {
        this.dapPort = dapPort;
        this.dapClient = new DapClient();
        this.dapClient.setListener(this);
    }
    
    // ========== DebugBackend Implementation ==========

    @Override
    public BackendType getType() {
        return BackendType.JAVASCRIPT;
    }

    @Override
    public void initialize(BackendEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        log.info("Starting JavaScriptBackend, connecting to DAP port {}", dapPort);

        dapClient.connect("127.0.0.1", dapPort)
            .thenCompose(v -> {
                // Send initialize request
                JsonObject initArgs = new JsonObject();
                initArgs.addProperty("clientID", "karate-debug");
                initArgs.addProperty("clientName", "Karate Debug");
                initArgs.addProperty("adapterID", "graalvm");
                initArgs.addProperty("linesStartAt1", true);
                initArgs.addProperty("columnsStartAt1", true);
                initArgs.addProperty("pathFormat", "path");
                initArgs.addProperty("supportsVariableType", true);
                initArgs.addProperty("supportsVariablePaging", false);
                initArgs.addProperty("supportsRunInTerminalRequest", false);
                return dapClient.send("initialize", initArgs);
            })
            .thenCompose(capabilities -> {
                log.debug("DAP capabilities: {}", capabilities);
                // Send attach request to attach to the running JS context
                JsonObject attachArgs = new JsonObject();
                return dapClient.send("attach", attachArgs);
            })
            .thenCompose(v -> {
                // Send configurationDone
                return dapClient.send("configurationDone", null);
            })
            .thenAccept(v -> {
                log.info("JavaScriptBackend ready");
                ready = true;
            })
            .exceptionally(e -> {
                log.error("Failed to start JavaScriptBackend", e);
                return null;
            });
    }

    @Override
    public void stop() {
        log.info("Stopping JavaScriptBackend");
        ready = false;
        try {
            dapClient.send("disconnect", null);
        } catch (Exception e) {
            log.debug("Error sending disconnect", e);
        }
        dapClient.disconnect();
    }

    @Override
    public boolean isReady() {
        return ready && dapClient.isConnected();
    }

    @Override
    public boolean canHandleFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs");
    }
    
    @Override
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> breakpoints) {
        List<Breakpoint> results = new ArrayList<>();

        try {
            // DAP uses setBreakpoints with source and breakpoint list
            JsonObject args = new JsonObject();

            // Set source
            JsonObject source = new JsonObject();
            source.addProperty("path", filePath);
            args.add("source", source);

            // Set breakpoints array
            JsonArray bpArray = new JsonArray();
            for (BreakpointRequest req : breakpoints) {
                JsonObject bp = new JsonObject();
                bp.addProperty("line", req.line());
                if (req.hasCondition()) {
                    bp.addProperty("condition", req.condition());
                }
                bpArray.add(bp);
            }
            args.add("breakpoints", bpArray);

            JsonObject result = dapClient.sendSync("setBreakpoints", args);

            // Parse response breakpoints
            if (result != null && result.has("breakpoints")) {
                JsonArray responseBps = result.getAsJsonArray("breakpoints");
                for (int i = 0; i < responseBps.size(); i++) {
                    JsonObject bp = responseBps.get(i).getAsJsonObject();
                    int bpId = nextBreakpointId.getAndIncrement();
                    boolean verified = bp.has("verified") && bp.get("verified").getAsBoolean();
                    int line = bp.has("line") ? bp.get("line").getAsInt() : breakpoints.get(i).line();

                    if (verified) {
                        results.add(Breakpoint.verified(bpId, line, filePath));
                    } else {
                        String message = bp.has("message") ? bp.get("message").getAsString() : "Not verified";
                        results.add(Breakpoint.unverified(bpId, line, filePath, message));
                    }
                    breakpointVerified.put(bpId, verified);
                }
            }
        } catch (Exception e) {
            log.error("Failed to set breakpoints for {}", filePath, e);
            // Return unverified breakpoints on error
            for (BreakpointRequest req : breakpoints) {
                results.add(Breakpoint.unverified(nextBreakpointId.getAndIncrement(),
                    req.line(), filePath, e.getMessage()));
            }
        }

        return results;
    }

    // ========== Execution Control ==========

    @Override
    public void resume(int threadId) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", threadId > 0 ? threadId : currentThreadId);
            dapClient.sendSync("continue", args);
            clearPauseState();
        } catch (Exception e) {
            log.error("Failed to resume", e);
        }
    }

    @Override
    public void stepOver(int threadId) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", threadId > 0 ? threadId : currentThreadId);
            dapClient.sendSync("next", args);
        } catch (Exception e) {
            log.error("Failed to step over", e);
        }
    }

    @Override
    public void stepInto(int threadId) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", threadId > 0 ? threadId : currentThreadId);
            dapClient.sendSync("stepIn", args);
        } catch (Exception e) {
            log.error("Failed to step into", e);
        }
    }

    @Override
    public void stepOut(int threadId) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", threadId > 0 ? threadId : currentThreadId);
            dapClient.sendSync("stepOut", args);
        } catch (Exception e) {
            log.error("Failed to step out", e);
        }
    }

    @Override
    public void pause(int threadId) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", threadId > 0 ? threadId : currentThreadId);
            dapClient.sendSync("pause", args);
        } catch (Exception e) {
            log.error("Failed to pause", e);
        }
    }

    // ========== Cross-Language Step Support ==========

    /**
     * Enables script entry catching for cross-language step-into.
     * With DAP, we can try to pause execution when JavaScript starts.
     */
    public void enableScriptEntry() {
        if (scriptEntryCatchingEnabled) {
            return; // Already enabled
        }

        scriptEntryCatchingEnabled = true;
        pendingStepIn = true;

        log.debug("Enabled script entry catching for cross-language step-into");

        // Try to pause immediately when JS starts executing
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", currentThreadId);
            dapClient.send("pause", args);
        } catch (Exception e) {
            log.debug("Could not send pause request: {}", e.getMessage());
        }
    }

    /**
     * Disables script entry catching.
     */
    public void disableScriptEntry() {
        if (!scriptEntryCatchingEnabled) {
            return;
        }

        pendingStepIn = false;
        scriptEntryCatchingEnabled = false;
        log.debug("Disabled script entry catching");
    }

    // ========== Inspection ==========

    @Override
    public List<StackFrame> getStackFrames(int threadId) {
        List<StackFrame> frames = new ArrayList<>();

        if (currentStackFrames == null) {
            return frames;
        }

        for (int i = 0; i < currentStackFrames.size(); i++) {
            JsonObject dapFrame = currentStackFrames.get(i).getAsJsonObject();
            int frameId = dapFrame.get("id").getAsInt();
            String functionName = dapFrame.has("name") ? dapFrame.get("name").getAsString() : "(anonymous)";
            int line = dapFrame.has("line") ? dapFrame.get("line").getAsInt() : 0;
            int column = dapFrame.has("column") ? dapFrame.get("column").getAsInt() : 0;

            String sourcePath = "unknown";
            String sourceName = "unknown";
            if (dapFrame.has("source")) {
                JsonObject source = dapFrame.getAsJsonObject("source");
                sourcePath = source.has("path") ? source.get("path").getAsString() : "unknown";
                sourceName = source.has("name") ? source.get("name").getAsString() : extractFileName(sourcePath);
            }

            frames.add(StackFrame.of(frameId, functionName, sourcePath, sourceName, line, column));
        }

        return frames;
    }

    @Override
    public List<Scope> getScopes(int frameId) {
        List<Scope> scopes = new ArrayList<>();

        try {
            JsonObject args = new JsonObject();
            args.addProperty("frameId", frameId);
            JsonObject result = dapClient.sendSync("scopes", args);

            if (result != null && result.has("scopes")) {
                JsonArray dapScopes = result.getAsJsonArray("scopes");
                for (int i = 0; i < dapScopes.size(); i++) {
                    JsonObject scope = dapScopes.get(i).getAsJsonObject();
                    String name = scope.has("name") ? scope.get("name").getAsString() : "Scope";
                    int varRef = scope.has("variablesReference") ? scope.get("variablesReference").getAsInt() : 0;

                    // Map our varRef to DAP's varRef
                    int ourVarRef = nextVarRef.getAndIncrement();
                    varRefToDapRef.put(ourVarRef, varRef);

                    scopes.add(Scope.of(name, ourVarRef));
                }
            }
        } catch (Exception e) {
            log.error("Failed to get scopes for frame {}", frameId, e);
        }

        return scopes;
    }

    @Override
    public List<Variable> getVariables(int variablesReference) {
        List<Variable> variables = new ArrayList<>();

        Integer dapRef = varRefToDapRef.get(variablesReference);
        if (dapRef == null) {
            dapRef = variablesReference;  // Fall back to direct reference
        }

        try {
            JsonObject args = new JsonObject();
            args.addProperty("variablesReference", dapRef);
            JsonObject result = dapClient.sendSync("variables", args);

            if (result != null && result.has("variables")) {
                JsonArray dapVars = result.getAsJsonArray("variables");
                for (int i = 0; i < dapVars.size(); i++) {
                    JsonObject v = dapVars.get(i).getAsJsonObject();
                    String name = v.has("name") ? v.get("name").getAsString() : "?";
                    String value = v.has("value") ? v.get("value").getAsString() : "";
                    String type = v.has("type") ? v.get("type").getAsString() : "";
                    int childRef = v.has("variablesReference") ? v.get("variablesReference").getAsInt() : 0;

                    if (childRef > 0) {
                        int ourChildRef = nextVarRef.getAndIncrement();
                        varRefToDapRef.put(ourChildRef, childRef);
                        variables.add(Variable.withChildren(name, value, type, ourChildRef));
                    } else {
                        variables.add(Variable.simple(name, value, type));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get variables for ref {}", variablesReference, e);
        }

        return variables;
    }

    @Override
    public EvaluateResult evaluate(int frameId, String expression, String context) {
        try {
            JsonObject args = new JsonObject();
            args.addProperty("expression", expression);
            args.addProperty("frameId", frameId);
            args.addProperty("context", context != null ? context : "watch");

            JsonObject result = dapClient.sendSync("evaluate", args);

            if (result != null) {
                String value = result.has("result") ? result.get("result").getAsString() : "";
                String type = result.has("type") ? result.get("type").getAsString() : "";
                int varRef = result.has("variablesReference") ? result.get("variablesReference").getAsInt() : 0;

                if (varRef > 0) {
                    int ourVarRef = nextVarRef.getAndIncrement();
                    varRefToDapRef.put(ourVarRef, varRef);
                    return EvaluateResult.withChildren(value, type, ourVarRef);
                }
                return EvaluateResult.simple(value, type);
            }
            return EvaluateResult.error("No result");
        } catch (Exception e) {
            log.error("Failed to evaluate expression: {}", expression, e);
            return EvaluateResult.error(e.getMessage());
        }
    }

    @Override
    public SetVariableResult setVariable(int variablesReference, String name, String value) {
        Integer dapRef = varRefToDapRef.get(variablesReference);
        if (dapRef == null) {
            dapRef = variablesReference;
        }

        try {
            JsonObject args = new JsonObject();
            args.addProperty("variablesReference", dapRef);
            args.addProperty("name", name);
            args.addProperty("value", value);

            JsonObject result = dapClient.sendSync("setVariable", args);

            if (result != null) {
                String newValue = result.has("value") ? result.get("value").getAsString() : value;
                String type = result.has("type") ? result.get("type").getAsString() : "unknown";
                int varRef = result.has("variablesReference") ? result.get("variablesReference").getAsInt() : 0;
                return new SetVariableResult(newValue, type, varRef);
            }
            return new SetVariableResult(value, "unknown", 0);
        } catch (Exception e) {
            log.error("Failed to set variable {} = {}", name, value, e);
            return new SetVariableResult(value, "error", 0);
        }
    }

    // ========== DapEventListener Implementation ==========

    @Override
    public void onStopped(JsonObject body) {
        String reason = body.has("reason") ? body.get("reason").getAsString() : "unknown";
        int threadId = body.has("threadId") ? body.get("threadId").getAsInt() : 1;

        log.debug("DAP stopped: reason={}, threadId={}", reason, threadId);

        // Fetch stack frames asynchronously to avoid blocking the DAP reader thread
        // (calling sendSync from the reader thread would cause deadlock)
        String description = body.has("description") ? body.get("description").getAsString() : reason;

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject args = new JsonObject();
                args.addProperty("threadId", threadId);
                JsonObject result = dapClient.sendSync("stackTrace", args);
                if (result != null && result.has("stackFrames")) {
                    currentStackFrames = result.getAsJsonArray("stackFrames");

                    // Check if this is an unwanted stop from internal/anonymous code
                    // GraalVM DAP sometimes pauses on internal script initialization
                    if ("debugger_statement".equals(reason) && currentStackFrames.size() > 0) {
                        JsonObject topFrame = currentStackFrames.get(0).getAsJsonObject();
                        JsonObject source = topFrame.has("source") ? topFrame.getAsJsonObject("source") : null;

                        // Check if source is internal (no path, or name is "Unnamed")
                        boolean isInternal = source == null
                            || (!source.has("path") && !source.has("name"))
                            || (source.has("name") && "Unnamed".equals(source.get("name").getAsString()));

                        if (isInternal) {
                            log.debug("Auto-continuing past internal debugger_statement in Unnamed source");
                            try {
                                JsonObject continueArgs = new JsonObject();
                                continueArgs.addProperty("threadId", threadId);
                                dapClient.send("continue", continueArgs);
                            } catch (Exception e) {
                                log.error("Failed to auto-continue", e);
                            }
                            return; // Don't notify listener about this internal stop
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to get stack frames on stop", e);
            }

            // Update pause state
            currentPauseReason = reason;
            currentThreadId = threadId;

            // Clear variable reference mappings for fresh allocation
            varRefToDapRef.clear();
            nextVarRef.set(1);

            // Notify listener after stack frames are fetched
            if (listener != null) {
                listener.onStopped(this, threadId, reason, description);
            }
        });
    }

    @Override
    public void onContinued(JsonObject body) {
        log.debug("DAP continued");
        clearPauseState();

        if (listener != null) {
            int threadId = body.has("threadId") ? body.get("threadId").getAsInt() : 1;
            boolean allThreadsContinued = body.has("allThreadsContinued") && body.get("allThreadsContinued").getAsBoolean();
            listener.onContinued(this, threadId, allThreadsContinued);
        }
    }

    @Override
    public void onTerminated() {
        log.info("DAP terminated");
        ready = false;

        if (listener != null) {
            listener.onTerminated(this);
        }
    }

    @Override
    public void onOutput(JsonObject body) {
        String category = body.has("category") ? body.get("category").getAsString() : "console";
        String output = body.has("output") ? body.get("output").getAsString() : "";

        log.debug("DAP output [{}]: {}", category, output.trim());

        if (listener != null) {
            listener.onOutput(this, category, output);
        }
    }

    @Override
    public void onLoadedSource(JsonObject body) {
        if (!body.has("source")) return;

        JsonObject source = body.getAsJsonObject("source");
        int sourceRef = source.has("sourceReference") ? source.get("sourceReference").getAsInt() : 0;
        String path = source.has("path") ? source.get("path").getAsString() : null;
        String name = source.has("name") ? source.get("name").getAsString() : null;

        log.debug("DAP source loaded: ref={}, path={}, name={}", sourceRef, path, name);

        if (sourceRef > 0 || path != null) {
            SourceInfo info = new SourceInfo(sourceRef, path, name);
            if (sourceRef > 0) {
                sources.put(sourceRef, info);
            }
            if (path != null && !path.isEmpty()) {
                String normalized = normalizePath(path);
                pathToSourceRef.put(normalized, sourceRef);
            }
        }
    }

    @Override
    public void onDisconnected(String reason) {
        log.info("DAP disconnected: reason={}", reason);
        ready = false;

        if (listener != null) {
            listener.onTerminated(this);
        }
    }

    @Override
    public void onError(Exception error) {
        log.error("DAP error", error);

        if (listener != null) {
            listener.onOutput(this, "stderr", "DAP error: " + error.getMessage());
        }
    }

    // ========== Helper Methods ==========

    private void clearPauseState() {
        currentStackFrames = null;
        currentPauseReason = null;
        varRefToDapRef.clear();
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        // Remove file:// prefix if present
        if (path.startsWith("file://")) {
            path = path.substring(7);
        }
        // Normalize to forward slashes
        return path.replace('\\', '/').toLowerCase();
    }

    private String extractFileName(String path) {
        if (path == null || path.isEmpty()) return "unknown";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String getScopeName(String type) {
        return switch (type) {
            case "local" -> "Local";
            case "closure" -> "Closure";
            case "catch" -> "Catch";
            case "block" -> "Block";
            case "script" -> "Script";
            case "eval" -> "Eval";
            case "global" -> "Global";
            case "module" -> "Module";
            case "wasm-expression-stack" -> "WASM Expression Stack";
            default -> type;
        };
    }

    // ========== Inner Classes ==========

    /**
     * Information about a loaded JavaScript source file.
     */
    private record SourceInfo(
        int sourceReference,
        String path,
        String name
    ) {}
}

