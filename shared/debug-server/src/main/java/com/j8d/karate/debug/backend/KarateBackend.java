package com.j8d.karate.debug.backend;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.j8d.karate.debug.ipc.IpcClientListener;
import com.j8d.karate.debug.ipc.IpcCommands;
import com.j8d.karate.debug.ipc.IpcEvents;
import com.j8d.karate.debug.ipc.IpcMessage;
import com.j8d.karate.debug.process.ChildProcessManager;

/**
 * DebugBackend implementation for Karate DSL debugging.
 * 
 * This backend communicates with the child Karate runner process via IPC.
 * It translates DebugBackend method calls to IPC commands and IPC events
 * to BackendEventListener callbacks.
 */
public class KarateBackend implements DebugBackend {

    private static final Logger log = LoggerFactory.getLogger(KarateBackend.class);

    private final ChildProcessManager processManager;
    private BackendEventListener listener;
    private volatile boolean ready = false;
    
    public KarateBackend(ChildProcessManager processManager) {
        this.processManager = processManager;
    }
    
    @Override
    public BackendType getType() {
        return BackendType.KARATE;
    }
    
    @Override
    public void initialize(BackendEventListener listener) {
        this.listener = listener;
        
        // Set up IPC event handling
        processManager.getIpcClient().setListener(new IpcClientListener() {
            @Override
            public void onEvent(IpcMessage event) {
                handleIpcEvent(event);
            }
            
            @Override
            public void onConnected() {
                log.debug("KarateBackend: IPC connected");
            }
            
            @Override
            public void onDisconnected(String reason) {
                log.info("KarateBackend: IPC disconnected - {}", reason);
                ready = false;
                if (listener != null) {
                    listener.onTerminated(KarateBackend.this);
                }
            }
            
            @Override
            public void onError(Exception error) {
                log.error("KarateBackend: IPC error", error);
            }
        });
    }
    
    @Override
    public void start() {
        log.trace("Starting KarateBackend");
        
        // Send start command to child
        JsonObject body = new JsonObject();
        sendCommand(IpcCommands.START, body);
        ready = true;
    }
    
    @Override
    public void stop() {
        log.trace("Stopping KarateBackend");
        ready = false;
        
        try {
            sendCommand(IpcCommands.STOP, null);
        } catch (Exception e) {
            log.debug("Error sending stop command", e);
        }
        
        processManager.stop();
    }
    
    @Override
    public boolean isReady() {
        return ready && processManager.isRunning();
    }
    
    @Override
    public boolean canHandleFile(String filePath) {
        return filePath != null && filePath.endsWith(".feature");
    }
    
    @Override
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> breakpoints) {
        JsonObject body = new JsonObject();
        body.addProperty("filePath", filePath);
        
        JsonArray bpArray = new JsonArray();
        for (BreakpointRequest bp : breakpoints) {
            JsonObject bpObj = new JsonObject();
            bpObj.addProperty("line", bp.line());
            if (bp.condition() != null) {
                bpObj.addProperty("condition", bp.condition());
            }
            bpArray.add(bpObj);
        }
        body.add("breakpoints", bpArray);
        
        try {
            IpcMessage response = sendCommandSync(IpcCommands.SET_BREAKPOINTS, body);
            return parseBreakpoints(response.getBody());
        } catch (Exception e) {
            log.error("Failed to set breakpoints", e);
            // Return unverified breakpoints
            List<Breakpoint> result = new ArrayList<>();
            int id = 1;
            for (BreakpointRequest bp : breakpoints) {
                result.add(Breakpoint.unverified(id++, bp.line(), filePath, "Failed to set breakpoint"));
            }
            return result;
        }
    }
    
    @Override
    public void resume(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        sendCommand(IpcCommands.RESUME, body);
    }
    
    @Override
    public void stepOver(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        sendCommand(IpcCommands.STEP_OVER, body);
    }
    
    @Override
    public void stepInto(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        sendCommand(IpcCommands.STEP_INTO, body);
    }
    
    @Override
    public void stepOut(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        sendCommand(IpcCommands.STEP_OUT, body);
    }
    
    @Override
    public void pause(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        sendCommand(IpcCommands.PAUSE, body);
    }
    
    @Override
    public List<StackFrame> getStackFrames(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);

        try {
            log.trace("Sending getStackFrames IPC request for threadId={}", threadId);
            IpcMessage response = sendCommandSync(IpcCommands.GET_STACK_FRAMES, body);
            log.trace("Received getStackFrames IPC response");
            return parseStackFrames(response.getBody());
        } catch (Exception e) {
            log.error("Failed to get stack frames", e);
            return List.of();
        }
    }

    @Override
    public List<Scope> getScopes(int frameId) {
        JsonObject body = new JsonObject();
        body.addProperty("frameId", frameId);

        try {
            IpcMessage response = sendCommandSync(IpcCommands.GET_SCOPES, body);
            return parseScopes(response.getBody());
        } catch (Exception e) {
            log.error("Failed to get scopes", e);
            return List.of();
        }
    }

    @Override
    public List<Variable> getVariables(int variablesReference) {
        JsonObject body = new JsonObject();
        body.addProperty("variablesReference", variablesReference);

        try {
            IpcMessage response = sendCommandSync(IpcCommands.GET_VARIABLES, body);
            return parseVariables(response.getBody());
        } catch (Exception e) {
            log.error("Failed to get variables", e);
            return List.of();
        }
    }

    @Override
    public EvaluateResult evaluate(int frameId, String expression, String context) {
        JsonObject body = new JsonObject();
        body.addProperty("frameId", frameId);
        body.addProperty("expression", expression);
        body.addProperty("context", context);

        try {
            IpcMessage response = sendCommandSync(IpcCommands.EVALUATE, body);
            JsonObject result = response.getBody();
            return new EvaluateResult(
                result.get("result").getAsString(),
                result.has("type") ? result.get("type").getAsString() : "unknown",
                result.has("variablesReference") ? result.get("variablesReference").getAsInt() : 0
            );
        } catch (Exception e) {
            log.error("Failed to evaluate expression", e);
            return EvaluateResult.error(e.getMessage());
        }
    }

    @Override
    public SetVariableResult setVariable(int variablesReference, String name, String value) {
        JsonObject body = new JsonObject();
        body.addProperty("variablesReference", variablesReference);
        body.addProperty("name", name);
        body.addProperty("value", value);

        try {
            IpcMessage response = sendCommandSync(IpcCommands.SET_VARIABLE, body);
            JsonObject result = response.getBody();
            return new SetVariableResult(
                result.get("value").getAsString(),
                result.has("type") ? result.get("type").getAsString() : "unknown",
                result.has("variablesReference") ? result.get("variablesReference").getAsInt() : 0
            );
        } catch (Exception e) {
            log.error("Failed to set variable", e);
            return SetVariableResult.simple(value, "error");
        }
    }

    // ========== IPC Helpers ==========

    private void sendCommand(String command, JsonObject body) {
        processManager.getIpcClient().sendCommand(command, body);
    }

    private IpcMessage sendCommandSync(String command, JsonObject body) throws Exception {
        return processManager.getIpcClient().sendCommand(command, body).get();
    }

    private void handleIpcEvent(IpcMessage event) {
        String eventName = event.getEvent();
        JsonObject body = event.getBody();

        switch (eventName) {
            case IpcEvents.STOPPED -> {
                int threadId = body.get("threadId").getAsInt();
                String reason = body.get("reason").getAsString();
                String description = body.has("description") ? body.get("description").getAsString() : null;
                if (listener != null) {
                    listener.onStopped(this, threadId, reason, description);
                }
            }
            case IpcEvents.CONTINUED -> {
                int threadId = body.get("threadId").getAsInt();
                boolean allThreads = body.has("allThreadsContinued") && body.get("allThreadsContinued").getAsBoolean();
                if (listener != null) {
                    listener.onContinued(this, threadId, allThreads);
                }
            }
            case IpcEvents.TERMINATED -> {
                if (listener != null) {
                    listener.onTerminated(this);
                }
            }
            case IpcEvents.OUTPUT -> {
                String category = body.get("category").getAsString();
                String output = body.get("output").getAsString();
                if (listener != null) {
                    listener.onOutput(this, category, output);
                }
            }
            case IpcEvents.BREAKPOINT_RESOLVED -> {
                int bpId = body.get("id").getAsInt();
                int line = body.get("line").getAsInt();
                String source = body.has("source") ? body.get("source").getAsString() : null;
                Breakpoint bp = new Breakpoint(bpId, true, line, source, null);
                log.trace("Received breakpoint resolved event: id={}, line={}", bpId, line);
                if (listener != null) {
                    listener.onBreakpointResolved(this, bp);
                }
            }
            default -> log.warn("Unknown IPC event: {}", eventName);
        }
    }

    // ========== Parsing Helpers ==========

    private List<Breakpoint> parseBreakpoints(JsonObject body) {
        List<Breakpoint> result = new ArrayList<>();
        if (body != null && body.has("breakpoints")) {
            int id = 1;
            for (var elem : body.getAsJsonArray("breakpoints")) {
                JsonObject bp = elem.getAsJsonObject();
                int bpId = bp.has("id") ? bp.get("id").getAsInt() : id++;
                int line = bp.get("line").getAsInt();
                boolean verified = bp.has("verified") && bp.get("verified").getAsBoolean();
                String source = bp.has("source") ? bp.get("source").getAsString() : null;
                String message = bp.has("message") ? bp.get("message").getAsString() : null;
                result.add(verified
                    ? Breakpoint.verified(bpId, line, source)
                    : Breakpoint.unverified(bpId, line, source, message));
            }
        }
        return result;
    }

    private List<StackFrame> parseStackFrames(JsonObject body) {
        List<StackFrame> result = new ArrayList<>();
        if (body != null && body.has("stackFrames")) {
            for (var elem : body.getAsJsonArray("stackFrames")) {
                JsonObject sf = elem.getAsJsonObject();
                result.add(new StackFrame(
                    sf.get("id").getAsInt(),
                    sf.get("name").getAsString(),
                    sf.has("sourcePath") ? sf.get("sourcePath").getAsString() : null,
                    sf.has("sourceName") ? sf.get("sourceName").getAsString() : null,
                    sf.has("line") ? sf.get("line").getAsInt() : 0,
                    sf.has("column") ? sf.get("column").getAsInt() : 1,
                    sf.has("presentationHint") ? sf.get("presentationHint").getAsString() : "normal"
                ));
            }
        }
        return result;
    }

    private List<Scope> parseScopes(JsonObject body) {
        List<Scope> result = new ArrayList<>();
        if (body != null && body.has("scopes")) {
            for (var elem : body.getAsJsonArray("scopes")) {
                JsonObject s = elem.getAsJsonObject();
                result.add(new Scope(
                    s.get("name").getAsString(),
                    s.get("variablesReference").getAsInt(),
                    s.has("namedVariables") ? s.get("namedVariables").getAsInt() : 0,
                    s.has("indexedVariables") ? s.get("indexedVariables").getAsInt() : 0,
                    s.has("expensive") && s.get("expensive").getAsBoolean()
                ));
            }
        }
        return result;
    }

    private List<Variable> parseVariables(JsonObject body) {
        List<Variable> result = new ArrayList<>();
        if (body != null && body.has("variables")) {
            for (var elem : body.getAsJsonArray("variables")) {
                JsonObject v = elem.getAsJsonObject();
                result.add(new Variable(
                    v.get("name").getAsString(),
                    v.get("value").getAsString(),
                    v.has("type") ? v.get("type").getAsString() : "unknown",
                    v.has("variablesReference") ? v.get("variablesReference").getAsInt() : 0,
                    v.has("namedVariables") ? v.get("namedVariables").getAsInt() : 0,
                    v.has("indexedVariables") ? v.get("indexedVariables").getAsInt() : 0,
                    v.has("evaluateName") ? v.get("evaluateName").getAsString() : v.get("name").getAsString()
                ));
            }
        }
        return result;
    }
}

