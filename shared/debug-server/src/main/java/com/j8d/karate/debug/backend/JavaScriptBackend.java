package com.j8d.karate.debug.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.j8d.karate.debug.cdp.CdpClient;
import com.j8d.karate.debug.cdp.CdpEventListener;

/**
 * DebugBackend implementation for JavaScript debugging via Chrome DevTools Protocol.
 * 
 * Connects to GraalVM's Chrome Inspector to debug JavaScript code executed
 * within Karate tests (karate-config.js, JS functions, etc.).
 */
public class JavaScriptBackend implements DebugBackend, CdpEventListener {
    
    private static final Logger log = LoggerFactory.getLogger(JavaScriptBackend.class);
    
    private final CdpClient cdpClient;
    private final String webSocketUrl;
    
    private BackendEventListener listener;
    private volatile boolean ready = false;
    
    // Script tracking: scriptId -> ScriptInfo
    private final Map<String, ScriptInfo> scripts = new ConcurrentHashMap<>();
    // Reverse lookup: normalized path -> scriptId
    private final Map<String, String> pathToScriptId = new ConcurrentHashMap<>();
    
    // Breakpoint tracking: our breakpoint ID -> CDP breakpoint ID
    private final Map<String, String> breakpointIdMap = new ConcurrentHashMap<>();
    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);
    
    // Current pause state
    private volatile JsonObject[] currentCallFrames;
    private volatile String currentPauseReason;
    
    // ID allocation for frames and variable references
    private final AtomicInteger nextFrameId = new AtomicInteger(1);
    private final AtomicInteger nextVarRef = new AtomicInteger(1);
    
    // Frame ID -> CDP callFrameId mapping
    private final Map<Integer, String> frameIdToCdpId = new ConcurrentHashMap<>();
    private final Map<String, Integer> cdpIdToFrameId = new ConcurrentHashMap<>();
    
    // Variable reference -> CDP object ID mapping
    private final Map<Integer, String> varRefToObjectId = new ConcurrentHashMap<>();
    
    /**
     * Creates a JavaScriptBackend that will connect to the given CDP endpoint.
     * 
     * @param webSocketUrl The WebSocket URL for the Chrome Inspector
     */
    public JavaScriptBackend(String webSocketUrl) {
        this.webSocketUrl = webSocketUrl;
        this.cdpClient = new CdpClient();
        this.cdpClient.setListener(this);
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
        log.info("Starting JavaScriptBackend, connecting to {}", webSocketUrl);
        
        cdpClient.connect(webSocketUrl)
            .thenCompose(v -> cdpClient.send("Debugger.enable", null))
            .thenCompose(v -> cdpClient.send("Runtime.enable", null))
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
        cdpClient.disconnect();
    }
    
    @Override
    public boolean isReady() {
        return ready && cdpClient.isConnected();
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
        
        // Find the script ID for this file
        String scriptId = findScriptIdForPath(filePath);
        if (scriptId == null) {
            log.warn("No script found for path: {}", filePath);
            // Return unverified breakpoints
            for (BreakpointRequest req : breakpoints) {
                results.add(Breakpoint.unverified(nextBreakpointId.getAndIncrement(),
                    req.line(), filePath, "Script not loaded"));
            }
            return results;
        }
        
        // Remove existing breakpoints for this file
        removeBreakpointsForFile(filePath);
        
        // Set new breakpoints
        for (BreakpointRequest req : breakpoints) {
            Breakpoint bp = setBreakpoint(scriptId, filePath, req);
            results.add(bp);
        }

        return results;
    }

    private Breakpoint setBreakpoint(String scriptId, String filePath, BreakpointRequest req) {
        int bpId = nextBreakpointId.getAndIncrement();

        try {
            JsonObject params = new JsonObject();
            JsonObject location = new JsonObject();
            location.addProperty("scriptId", scriptId);
            location.addProperty("lineNumber", req.line() - 1); // CDP uses 0-based lines
            params.add("location", location);

            if (req.hasCondition()) {
                params.addProperty("condition", req.condition());
            }

            JsonObject result = cdpClient.sendSync("Debugger.setBreakpoint", params);

            String cdpBreakpointId = result.get("breakpointId").getAsString();
            breakpointIdMap.put(String.valueOf(bpId), cdpBreakpointId);

            JsonObject actualLocation = result.getAsJsonObject("actualLocation");
            int actualLine = actualLocation.get("lineNumber").getAsInt() + 1; // Convert to 1-based

            return Breakpoint.verified(bpId, actualLine, filePath);

        } catch (Exception e) {
            log.error("Failed to set breakpoint at {}:{}", filePath, req.line(), e);
            return Breakpoint.unverified(bpId, req.line(), filePath, e.getMessage());
        }
    }

    private void removeBreakpointsForFile(String filePath) {
        // Find and remove all breakpoints for this file
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> entry : breakpointIdMap.entrySet()) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("breakpointId", entry.getValue());
                cdpClient.sendSync("Debugger.removeBreakpoint", params);
                toRemove.add(entry.getKey());
            } catch (Exception e) {
                log.warn("Failed to remove breakpoint {}", entry.getValue(), e);
            }
        }
        toRemove.forEach(breakpointIdMap::remove);
    }

    // ========== Execution Control ==========

    @Override
    public void resume(int threadId) {
        try {
            cdpClient.sendSync("Debugger.resume", null);
            clearPauseState();
        } catch (Exception e) {
            log.error("Failed to resume", e);
        }
    }

    @Override
    public void stepOver(int threadId) {
        try {
            cdpClient.sendSync("Debugger.stepOver", null);
        } catch (Exception e) {
            log.error("Failed to step over", e);
        }
    }

    @Override
    public void stepInto(int threadId) {
        try {
            cdpClient.sendSync("Debugger.stepInto", null);
        } catch (Exception e) {
            log.error("Failed to step into", e);
        }
    }

    @Override
    public void stepOut(int threadId) {
        try {
            cdpClient.sendSync("Debugger.stepOut", null);
        } catch (Exception e) {
            log.error("Failed to step out", e);
        }
    }

    @Override
    public void pause(int threadId) {
        try {
            cdpClient.sendSync("Debugger.pause", null);
        } catch (Exception e) {
            log.error("Failed to pause", e);
        }
    }

    // ========== Inspection ==========

    @Override
    public List<StackFrame> getStackFrames(int threadId) {
        List<StackFrame> frames = new ArrayList<>();

        if (currentCallFrames == null) {
            return frames;
        }

        for (JsonObject cdpFrame : currentCallFrames) {
            String cdpFrameId = cdpFrame.get("callFrameId").getAsString();
            String functionName = cdpFrame.get("functionName").getAsString();
            if (functionName.isEmpty()) {
                functionName = "(anonymous)";
            }

            JsonObject location = cdpFrame.getAsJsonObject("location");
            String scriptId = location.get("scriptId").getAsString();
            int line = location.get("lineNumber").getAsInt() + 1; // Convert to 1-based
            int column = location.has("columnNumber") ?
                location.get("columnNumber").getAsInt() + 1 : 1;

            ScriptInfo script = scripts.get(scriptId);
            String sourcePath = script != null ? script.url : "unknown";
            String sourceName = extractFileName(sourcePath);

            int frameId = nextFrameId.getAndIncrement();
            frameIdToCdpId.put(frameId, cdpFrameId);
            cdpIdToFrameId.put(cdpFrameId, frameId);

            frames.add(StackFrame.of(frameId, functionName, sourcePath, sourceName, line, column));
        }

        return frames;
    }

    @Override
    public List<Scope> getScopes(int frameId) {
        List<Scope> scopes = new ArrayList<>();

        String cdpFrameId = frameIdToCdpId.get(frameId);
        if (cdpFrameId == null || currentCallFrames == null) {
            return scopes;
        }

        // Find the frame in currentCallFrames
        for (JsonObject cdpFrame : currentCallFrames) {
            if (cdpFrame.get("callFrameId").getAsString().equals(cdpFrameId)) {
                JsonArray scopeChain = cdpFrame.getAsJsonArray("scopeChain");
                for (int i = 0; i < scopeChain.size(); i++) {
                    JsonObject scope = scopeChain.get(i).getAsJsonObject();
                    String type = scope.get("type").getAsString();
                    String name = getScopeName(type);

                    JsonObject object = scope.getAsJsonObject("object");
                    String objectId = object.get("objectId").getAsString();

                    int varRef = nextVarRef.getAndIncrement();
                    varRefToObjectId.put(varRef, objectId);

                    scopes.add(Scope.of(name, varRef));
                }
                break;
            }
        }

        return scopes;
    }

    @Override
    public List<Variable> getVariables(int variablesReference) {
        List<Variable> variables = new ArrayList<>();

        String objectId = varRefToObjectId.get(variablesReference);
        if (objectId == null) {
            return variables;
        }

        try {
            JsonObject params = new JsonObject();
            params.addProperty("objectId", objectId);
            params.addProperty("ownProperties", true);

            JsonObject result = cdpClient.sendSync("Runtime.getProperties", params);
            JsonArray properties = result.getAsJsonArray("result");

            for (int i = 0; i < properties.size(); i++) {
                JsonObject prop = properties.get(i).getAsJsonObject();
                String name = prop.get("name").getAsString();

                if (!prop.has("value")) continue;

                JsonObject value = prop.getAsJsonObject("value");
                Variable var = createVariable(name, value);
                variables.add(var);
            }
        } catch (Exception e) {
            log.error("Failed to get variables for ref {}", variablesReference, e);
        }

        return variables;
    }

    @Override
    public EvaluateResult evaluate(int frameId, String expression, String context) {
        String cdpFrameId = frameIdToCdpId.get(frameId);

        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", expression);
            if (cdpFrameId != null) {
                params.addProperty("callFrameId", cdpFrameId);
            }
            params.addProperty("returnByValue", false);

            JsonObject result = cdpClient.sendSync("Debugger.evaluateOnCallFrame", params);

            if (result.has("exceptionDetails")) {
                JsonObject exception = result.getAsJsonObject("exceptionDetails");
                String errorText = exception.has("text") ?
                    exception.get("text").getAsString() : "Evaluation error";
                return EvaluateResult.error(errorText);
            }

            JsonObject remoteObject = result.getAsJsonObject("result");
            return createEvaluateResult(remoteObject);

        } catch (Exception e) {
            log.error("Failed to evaluate expression: {}", expression, e);
            return EvaluateResult.error(e.getMessage());
        }
    }

    @Override
    public SetVariableResult setVariable(int variablesReference, String name, String value) {
        // JavaScript variable modification via CDP
        String objectId = varRefToObjectId.get(variablesReference);
        if (objectId == null) {
            return new SetVariableResult(value, "unknown", 0);
        }

        try {
            // Use Runtime.callFunctionOn to set the property
            JsonObject params = new JsonObject();
            params.addProperty("objectId", objectId);
            params.addProperty("functionDeclaration",
                "function(name, value) { this[name] = value; return this[name]; }");

            JsonArray args = new JsonArray();
            JsonObject nameArg = new JsonObject();
            nameArg.addProperty("value", name);
            args.add(nameArg);

            JsonObject valueArg = new JsonObject();
            valueArg.addProperty("value", value);
            args.add(valueArg);

            params.add("arguments", args);
            params.addProperty("returnByValue", true);

            JsonObject result = cdpClient.sendSync("Runtime.callFunctionOn", params);
            JsonObject returnValue = result.getAsJsonObject("result");

            String newValue = returnValue.has("value") ?
                returnValue.get("value").toString() : value;
            String type = returnValue.has("type") ?
                returnValue.get("type").getAsString() : "unknown";

            return new SetVariableResult(newValue, type, 0);

        } catch (Exception e) {
            log.error("Failed to set variable {} = {}", name, value, e);
            return new SetVariableResult(value, "error", 0);
        }
    }

    // ========== CdpEventListener Implementation ==========

    @Override
    public void onScriptParsed(String scriptId, String url, int startLine, int startColumn,
                               int endLine, int endColumn, String hash) {
        log.debug("Script parsed: {} -> {}", scriptId, url);

        ScriptInfo info = new ScriptInfo(scriptId, url, startLine, endLine, hash);
        scripts.put(scriptId, info);

        if (url != null && !url.isEmpty()) {
            String normalizedPath = normalizePath(url);
            pathToScriptId.put(normalizedPath, scriptId);
        }
    }

    @Override
    public void onPaused(JsonObject[] callFrames, String reason, String[] hitBreakpoints, JsonObject data) {
        log.debug("Paused: reason={}, frames={}", reason, callFrames.length);

        currentCallFrames = callFrames;
        currentPauseReason = reason;

        // Clear frame ID mappings for fresh allocation
        frameIdToCdpId.clear();
        cdpIdToFrameId.clear();
        varRefToObjectId.clear();
        nextFrameId.set(1);
        nextVarRef.set(1);

        if (listener != null) {
            // JavaScript is single-threaded, use thread ID 1
            String description = reason;
            if (hitBreakpoints != null && hitBreakpoints.length > 0) {
                description = "Breakpoint hit";
            }
            listener.onStopped(this, 1, reason, description);
        }
    }

    @Override
    public void onResumed() {
        log.debug("Resumed");
        clearPauseState();

        if (listener != null) {
            listener.onContinued(this, 1, true);
        }
    }

    @Override
    public void onBreakpointResolved(String breakpointId, JsonObject location) {
        log.debug("Breakpoint resolved: {} at {}", breakpointId, location);
        // Could notify listener about breakpoint verification
    }

    @Override
    public void onDisconnected(int code, String reason, boolean remote) {
        log.info("CDP disconnected: code={}, reason={}", code, reason);
        ready = false;

        if (listener != null) {
            listener.onTerminated(this);
        }
    }

    @Override
    public void onError(Exception error) {
        log.error("CDP error", error);

        if (listener != null) {
            listener.onOutput(this, "stderr", "CDP error: " + error.getMessage());
        }
    }

    // ========== Helper Methods ==========

    private void clearPauseState() {
        currentCallFrames = null;
        currentPauseReason = null;
        frameIdToCdpId.clear();
        cdpIdToFrameId.clear();
        varRefToObjectId.clear();
    }

    private String findScriptIdForPath(String filePath) {
        String normalized = normalizePath(filePath);
        return pathToScriptId.get(normalized);
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

    private Variable createVariable(String name, JsonObject remoteObject) {
        String type = remoteObject.has("type") ? remoteObject.get("type").getAsString() : "undefined";
        String subtype = remoteObject.has("subtype") ? remoteObject.get("subtype").getAsString() : null;

        String displayValue;
        int varRef = 0;

        if ("undefined".equals(type)) {
            displayValue = "undefined";
        } else if ("object".equals(type)) {
            if ("null".equals(subtype)) {
                displayValue = "null";
            } else {
                String className = remoteObject.has("className") ?
                    remoteObject.get("className").getAsString() : "Object";
                displayValue = className;

                // Objects can be expanded
                if (remoteObject.has("objectId")) {
                    varRef = nextVarRef.getAndIncrement();
                    varRefToObjectId.put(varRef, remoteObject.get("objectId").getAsString());
                }
            }
        } else if ("function".equals(type)) {
            String desc = remoteObject.has("description") ?
                remoteObject.get("description").getAsString() : "function";
            // Truncate long function descriptions
            displayValue = desc.length() > 50 ? desc.substring(0, 47) + "..." : desc;
        } else {
            // Primitives: string, number, boolean, symbol, bigint
            displayValue = remoteObject.has("value") ?
                remoteObject.get("value").toString() : type;
        }

        String displayType = subtype != null ? subtype : type;
        return Variable.withChildren(name, displayValue, displayType, varRef);
    }

    private EvaluateResult createEvaluateResult(JsonObject remoteObject) {
        String type = remoteObject.has("type") ? remoteObject.get("type").getAsString() : "undefined";

        String displayValue;
        int varRef = 0;

        if ("object".equals(type) && remoteObject.has("objectId")) {
            String className = remoteObject.has("className") ?
                remoteObject.get("className").getAsString() : "Object";
            displayValue = className;
            varRef = nextVarRef.getAndIncrement();
            varRefToObjectId.put(varRef, remoteObject.get("objectId").getAsString());
        } else if (remoteObject.has("value")) {
            displayValue = remoteObject.get("value").toString();
        } else if (remoteObject.has("description")) {
            displayValue = remoteObject.get("description").getAsString();
        } else {
            displayValue = type;
        }

        return EvaluateResult.withChildren(displayValue, type, varRef);
    }

    // ========== Inner Classes ==========

    /**
     * Information about a loaded JavaScript script.
     */
    private record ScriptInfo(
        String scriptId,
        String url,
        int startLine,
        int endLine,
        String hash
    ) {}
}

