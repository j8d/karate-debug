package com.j8d.karate.debug.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    // Track when we're actively stepping within JavaScript (step into/over/out was initiated)
    private volatile boolean isSteppingInJs = false;

    // Source content matcher for mapping "Unnamed" sources to .js files
    private final JavaScriptSourceMatcher sourceMatcher;

    // Pending breakpoints: file path -> breakpoint requests (for re-applying after source match)
    private final Map<String, List<BreakpointRequest>> pendingBreakpoints = new ConcurrentHashMap<>();

    /**
     * Creates a JavaScriptBackend that will connect to the given DAP server port.
     *
     * @param dapPort The port for the GraalVM DAP server
     * @param workspaceRoot The workspace root for scanning .js files (can be null)
     */
    public JavaScriptBackend(int dapPort, Path workspaceRoot) {
        this.dapPort = dapPort;
        this.dapClient = new DapClient();
        this.dapClient.setListener(this);

        // Initialize source matcher if workspace is provided
        if (workspaceRoot != null) {
            this.sourceMatcher = new JavaScriptSourceMatcher(workspaceRoot);
            this.sourceMatcher.scanWorkspace();
        } else {
            this.sourceMatcher = null;
        }
    }

    /**
     * Creates a JavaScriptBackend that will connect to the given DAP server port.
     * No source matching will be available.
     *
     * @param dapPort The port for the GraalVM DAP server
     */
    public JavaScriptBackend(int dapPort) {
        this(dapPort, null);
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
        log.trace("Starting JavaScriptBackend, connecting to DAP port {}", dapPort);

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
                log.trace("DAP capabilities: {}", capabilities);
                // Send attach request to attach to the running JS context
                JsonObject attachArgs = new JsonObject();
                return dapClient.send("attach", attachArgs);
            })
            .thenCompose(v -> {
                // Send configurationDone
                return dapClient.send("configurationDone", null);
            })
            .thenAccept(v -> {
                log.debug("JavaScriptBackend ready");
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

            // Set source - check if we have a sourceReference mapping for this file
            JsonObject source = new JsonObject();
            Optional<Integer> sourceRef = Optional.empty();

            if (sourceMatcher != null) {
                sourceRef = sourceMatcher.getSourceRefForPath(filePath);
            }

            if (sourceRef.isPresent()) {
                // Use sourceReference for "Unnamed" sources that we've matched
                source.addProperty("sourceReference", sourceRef.get());
                log.trace("Setting breakpoints using sourceReference={} for {}", sourceRef.get(), filePath);
            } else {
                // No sourceRef mapping yet - store as pending and try path-based
                // The breakpoints will be re-applied once the source is loaded and matched
                String normalizedPath = normalizePath(filePath);
                pendingBreakpoints.put(normalizedPath, new ArrayList<>(breakpoints));
                log.trace("Storing {} pending breakpoints for {} (no sourceRef mapping yet)",
                        breakpoints.size(), filePath);

                // Fall back to path-based breakpoints (likely won't verify but we try anyway)
                source.addProperty("path", filePath);
            }
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
                        log.info("Breakpoint verified: {}:{}", filePath, line);
                    } else {
                        String message = bp.has("message") ? bp.get("message").getAsString() : "Not verified";
                        results.add(Breakpoint.unverified(bpId, line, filePath, message));
                        log.debug("Breakpoint not verified: {}:{} - {}", filePath, line, message);
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
            isSteppingInJs = true;  // Mark that we're stepping
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
            isSteppingInJs = true;  // Mark that we're stepping
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
            isSteppingInJs = true;  // Mark that we're stepping
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

        log.trace("Enabled script entry catching for cross-language step-into");

        // Try to pause immediately when JS starts executing
        try {
            JsonObject args = new JsonObject();
            args.addProperty("threadId", currentThreadId);
            dapClient.send("pause", args);
        } catch (Exception e) {
            log.trace("Could not send pause request: {}", e.getMessage());
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
        log.trace("Disabled script entry catching");
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

                // Check if this is an "Unnamed" source that we've mapped to a file
                int sourceRef = source.has("sourceReference") ? source.get("sourceReference").getAsInt() : 0;
                String dapSourceName = source.has("name") ? source.get("name").getAsString() : null;

                if ("Unnamed".equals(dapSourceName) && sourceRef > 0 && sourceMatcher != null) {
                    // Try to get the mapped file path
                    Optional<Path> mappedPath = sourceMatcher.getPathForSourceRef(sourceRef);
                    if (mappedPath.isPresent()) {
                        sourcePath = mappedPath.get().toString();
                        sourceName = mappedPath.get().getFileName().toString();
                        log.trace("Translated 'Unnamed' source ref={} to {}", sourceRef, sourcePath);
                    } else {
                        sourcePath = source.has("path") ? source.get("path").getAsString() : "Unnamed";
                        sourceName = "Unnamed";
                    }
                } else {
                    sourcePath = source.has("path") ? source.get("path").getAsString() : "unknown";
                    sourceName = source.has("name") ? source.get("name").getAsString() : extractFileName(sourcePath);
                }
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

        log.trace("DAP stopped: reason={}, threadId={}", reason, threadId);

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

                    // Determine if we should pause or auto-continue
                    // GraalVM DAP stops on every script execution with "debugger_statement"
                    // We should only pause if:
                    // 1. We're stepping in JS (isSteppingInJs), OR
                    // 2. We're in cross-language step-into mode (scriptEntryCatchingEnabled), OR
                    // 3. There's a breakpoint (reason would be "breakpoint", not "debugger_statement")

                    if ("debugger_statement".equals(reason) && currentStackFrames.size() > 0) {
                        JsonObject topFrame = currentStackFrames.get(0).getAsJsonObject();
                        JsonObject source = topFrame.has("source") ? topFrame.getAsJsonObject("source") : null;

                        // Check if source is mapped to a user file
                        boolean isMappedToFile = false;
                        if (source != null && source.has("name") && "Unnamed".equals(source.get("name").getAsString())) {
                            int sourceRef = source.has("sourceReference") ? source.get("sourceReference").getAsInt() : 0;
                            if (sourceMatcher != null && sourceRef > 0) {
                                Optional<Path> mappedPath = sourceMatcher.getPathForSourceRef(sourceRef);
                                isMappedToFile = mappedPath.isPresent();
                            }
                        } else if (source != null && source.has("path")) {
                            isMappedToFile = true; // Has a real path
                        }

                        // Decide whether to pause, step-into, or continue
                        boolean shouldPause = false;
                        boolean shouldStepInto = false;
                        if (!isMappedToFile) {
                            // Not a user file (inline snippet like karate.log() or jsHelper.processOrder())
                            if (scriptEntryCatchingEnabled) {
                                // Cross-language step-into: step INTO the inline snippet to reach the function body
                                // This is needed because GraalVM DAP only stops on script entry, not function entry
                                log.trace("Stepping into inline snippet to reach function body");
                                shouldStepInto = true;
                            } else {
                                // Not in step mode - just skip
                                log.trace("Auto-continuing past inline snippet (not mapped to file)");
                            }
                        } else if (isSteppingInJs) {
                            // We're stepping within JS - pause
                            log.trace("Pausing: stepping in JS");
                            shouldPause = true;
                        } else if (scriptEntryCatchingEnabled) {
                            // Cross-language step-into - pause on user files only
                            log.trace("Pausing: cross-language step-into on mapped file");
                            shouldPause = true;
                        } else {
                            // Not stepping, not cross-language - this is just script initialization
                            // TODO: Check for breakpoints at this line
                            log.trace("Auto-continuing: not stepping, script initialization");
                        }

                        if (shouldStepInto) {
                            try {
                                // Step into the inline snippet to reach the actual function
                                JsonObject stepArgs = new JsonObject();
                                stepArgs.addProperty("threadId", threadId);
                                dapClient.send("stepIn", stepArgs);
                            } catch (Exception e) {
                                log.error("Failed to step into", e);
                            }
                            return; // Don't notify listener - we're stepping deeper
                        } else if (!shouldPause) {
                            try {
                                JsonObject continueArgs = new JsonObject();
                                continueArgs.addProperty("threadId", threadId);
                                dapClient.send("continue", continueArgs);
                            } catch (Exception e) {
                                log.error("Failed to auto-continue", e);
                            }
                            return; // Don't notify listener about this stop
                        }
                    }

                    // Clear stepping flag - we've stopped
                    isSteppingInJs = false;

                    // If we're in cross-language step mode and stopped, disable it now
                    if (scriptEntryCatchingEnabled) {
                        log.trace("Cross-language step-into caught JavaScript execution");
                        scriptEntryCatchingEnabled = false;
                        pendingStepIn = false;
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
                // Build a better description with source path and line number (like Java does)
                String stoppedDescription = description;
                if (currentStackFrames != null && currentStackFrames.size() > 0) {
                    JsonObject topFrame = currentStackFrames.get(0).getAsJsonObject();
                    int line = topFrame.has("line") ? topFrame.get("line").getAsInt() : 0;

                    // Try to get the source path
                    String sourcePath = null;
                    if (topFrame.has("source")) {
                        JsonObject source = topFrame.getAsJsonObject("source");
                        int sourceRef = source.has("sourceReference") ? source.get("sourceReference").getAsInt() : 0;
                        String sourceName = source.has("name") ? source.get("name").getAsString() : null;

                        if ("Unnamed".equals(sourceName) && sourceRef > 0 && sourceMatcher != null) {
                            Optional<Path> mappedPath = sourceMatcher.getPathForSourceRef(sourceRef);
                            if (mappedPath.isPresent()) {
                                sourcePath = mappedPath.get().toString();
                            }
                        } else if (source.has("path")) {
                            sourcePath = source.get("path").getAsString();
                        }
                    }

                    if (sourcePath != null) {
                        stoppedDescription = sourcePath + ":" + line;
                    }
                }
                listener.onStopped(this, threadId, reason, stoppedDescription);
            }
        });
    }

    @Override
    public void onContinued(JsonObject body) {
        log.trace("DAP continued");
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

        log.trace("DAP output [{}]: {}", category, output.trim());

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

        log.trace("DAP source loaded: ref={}, path={}, name={}", sourceRef, path, name);

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

        // For "Unnamed" sources, try to match content to a .js file
        if ("Unnamed".equals(name) && sourceRef > 0 && sourceMatcher != null) {
            matchUnnamedSourceToFile(sourceRef);
        }
    }

    /**
     * Fetches the content of an "Unnamed" source and attempts to match it to a .js file.
     * If matched, registers the mapping for breakpoint and stack frame translation.
     */
    private void matchUnnamedSourceToFile(int sourceRef) {
        JsonObject args = new JsonObject();
        // DAP spec: source request requires a nested "source" object with sourceReference
        JsonObject sourceObj = new JsonObject();
        sourceObj.addProperty("sourceReference", sourceRef);
        args.add("source", sourceObj);
        // Also include sourceReference at top level for compatibility
        args.addProperty("sourceReference", sourceRef);

        // IMPORTANT: Use thenAcceptAsync to run on a separate thread.
        // The default thenAccept runs on the DAP reader thread, and if we call sendSync
        // inside the callback, we'd deadlock (reader thread waiting for response that
        // only the reader thread can process).
        dapClient.send("source", args).thenAcceptAsync(body -> {
            // Note: DapClient.handleResponse() extracts the body, so 'body' IS the response body
            if (body != null && body.has("content")) {
                String content = body.get("content").getAsString();

                // Try to match content to a known .js file
                Optional<Path> matchedFile = sourceMatcher.matchContent(content);

                if (matchedFile.isPresent()) {
                    Path filePath = matchedFile.get();
                    sourceMatcher.registerMapping(sourceRef, filePath);

                    // Also update our internal path mapping
                    String normalizedPath = normalizePath(filePath.toString());
                    pathToSourceRef.put(normalizedPath, sourceRef);

                    // Update the SourceInfo with the real path
                    SourceInfo info = sources.get(sourceRef);
                    if (info != null) {
                        sources.put(sourceRef, new SourceInfo(sourceRef, filePath.toString(), filePath.getFileName().toString()));
                    }

                    log.trace("Matched 'Unnamed' source ref={} to file: {}", sourceRef, filePath);

                    // Re-apply any pending breakpoints for this file
                    reapplyPendingBreakpoints(normalizedPath, sourceRef);
                } else {
                    // Log for debugging - this might be inline JS or a transformed source
                    String preview = content.length() > 200
                        ? content.substring(0, 200) + "...[truncated]"
                        : content;
                    log.trace("No match for 'Unnamed' source ref={} ({} chars): {}",
                        sourceRef, content.length(), preview);
                }
            }
        }).exceptionally(e -> {
            log.warn("Failed to fetch source content for ref={}: {}", sourceRef, e.getMessage());
            return null;
        });
    }

    /**
     * Re-applies pending breakpoints for a file after its source has been matched.
     * Called when an "Unnamed" source is matched to a .js file.
     *
     * @param normalizedPath The normalized file path
     * @param sourceRef The sourceReference for the matched source
     */
    private void reapplyPendingBreakpoints(String normalizedPath, int sourceRef) {
        List<BreakpointRequest> pending = pendingBreakpoints.remove(normalizedPath);
        if (pending == null || pending.isEmpty()) {
            log.trace("No pending breakpoints for {}", normalizedPath);
            return;
        }

        log.info("Re-applying {} pending breakpoints for {} using sourceRef={}",
                pending.size(), normalizedPath, sourceRef);

        try {
            // Build DAP setBreakpoints request using sourceReference
            JsonObject args = new JsonObject();
            JsonObject source = new JsonObject();
            source.addProperty("sourceReference", sourceRef);
            args.add("source", source);

            JsonArray bpArray = new JsonArray();
            for (BreakpointRequest req : pending) {
                JsonObject bp = new JsonObject();
                bp.addProperty("line", req.line());
                if (req.hasCondition()) {
                    bp.addProperty("condition", req.condition());
                }
                bpArray.add(bp);
            }
            args.add("breakpoints", bpArray);

            JsonObject result = dapClient.sendSync("setBreakpoints", args);

            // Log results
            if (result != null && result.has("breakpoints")) {
                JsonArray responseBps = result.getAsJsonArray("breakpoints");
                int verified = 0;
                for (int i = 0; i < responseBps.size(); i++) {
                    JsonObject bp = responseBps.get(i).getAsJsonObject();
                    boolean isVerified = bp.has("verified") && bp.get("verified").getAsBoolean();
                    if (isVerified) {
                        verified++;
                        int line = bp.has("line") ? bp.get("line").getAsInt() : pending.get(i).line();
                        log.info("Breakpoint now verified: {}:{}", normalizedPath, line);
                    }
                }
                log.info("Re-applied breakpoints: {}/{} verified for {}", verified, pending.size(), normalizedPath);
            }
        } catch (Exception e) {
            log.error("Failed to re-apply breakpoints for {}", normalizedPath, e);
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

