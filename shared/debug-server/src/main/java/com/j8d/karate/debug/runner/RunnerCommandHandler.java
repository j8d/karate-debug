package com.j8d.karate.debug.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.j8d.karate.debug.ipc.IpcCommands;
import com.j8d.karate.debug.ipc.IpcServerHandler;

/**
 * Handles IPC commands from the parent process in the child runner.
 * 
 * This class bridges IPC commands to the Karate debugger operations.
 * It will be expanded as we implement the full debugging functionality.
 */
public class RunnerCommandHandler implements IpcServerHandler {
    
    private static final Logger log = LoggerFactory.getLogger(RunnerCommandHandler.class);
    
    private final KarateRunner runner;
    private RunnerDebugger debugger;
    
    public RunnerCommandHandler(KarateRunner runner) {
        this.runner = runner;
    }
    
    @Override
    public JsonObject handleCommand(String command, JsonObject body) throws Exception {
        log.debug("Handling command: {} with body: {}", command, body);
        
        return switch (command) {
            case IpcCommands.START -> handleStart(body);
            case IpcCommands.STOP -> handleStop(body);
            case IpcCommands.SET_BREAKPOINTS -> handleSetBreakpoints(body);
            case IpcCommands.RESUME -> handleResume(body);
            case IpcCommands.STEP_OVER -> handleStepOver(body);
            case IpcCommands.STEP_INTO -> handleStepInto(body);
            case IpcCommands.STEP_OUT -> handleStepOut(body);
            case IpcCommands.PAUSE -> handlePause(body);
            case IpcCommands.GET_STACK_FRAMES -> handleGetStackFrames(body);
            case IpcCommands.GET_SCOPES -> handleGetScopes(body);
            case IpcCommands.GET_VARIABLES -> handleGetVariables(body);
            case IpcCommands.EVALUATE -> handleEvaluate(body);
            case IpcCommands.SET_VARIABLE -> handleSetVariable(body);
            default -> {
                log.warn("Unknown command: {}", command);
                throw new IllegalArgumentException("Unknown command: " + command);
            }
        };
    }
    
    private JsonObject handleStart(JsonObject body) {
        log.info("Starting Karate execution");
        
        // Create debugger and start execution
        // This will be implemented when we refactor KarateDebugger
        debugger = new RunnerDebugger(runner);
        debugger.start();
        
        return null; // Simple acknowledgment
    }
    
    private JsonObject handleStop(JsonObject body) {
        log.info("Stopping Karate execution");
        
        if (debugger != null) {
            debugger.stop();
        }
        runner.shutdown();
        
        return null;
    }
    
    private JsonObject handleSetBreakpoints(JsonObject body) {
        String filePath = body.get("filePath").getAsString();
        // breakpoints array will be parsed and set
        log.debug("Setting breakpoints in: {}", filePath);
        
        if (debugger != null) {
            return debugger.setBreakpoints(filePath, body.getAsJsonArray("breakpoints"));
        }
        return new JsonObject();
    }
    
    private JsonObject handleResume(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        log.debug("Resuming thread: {}", threadId);
        
        if (debugger != null) {
            debugger.resume(threadId);
        }
        return null;
    }
    
    private JsonObject handleStepOver(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        log.debug("Step over on thread: {}", threadId);
        
        if (debugger != null) {
            debugger.stepOver(threadId);
        }
        return null;
    }
    
    private JsonObject handleStepInto(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        log.debug("Step into on thread: {}", threadId);
        
        if (debugger != null) {
            debugger.stepInto(threadId);
        }
        return null;
    }
    
    private JsonObject handleStepOut(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        log.debug("Step out on thread: {}", threadId);
        
        if (debugger != null) {
            debugger.stepOut(threadId);
        }
        return null;
    }
    
    private JsonObject handlePause(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        log.debug("Pause thread: {}", threadId);
        
        if (debugger != null) {
            debugger.pause(threadId);
        }
        return null;
    }
    
    private JsonObject handleGetStackFrames(JsonObject body) {
        int threadId = body.get("threadId").getAsInt();
        
        if (debugger != null) {
            return debugger.getStackFrames(threadId);
        }
        return new JsonObject();
    }
    
    private JsonObject handleGetScopes(JsonObject body) {
        int frameId = body.get("frameId").getAsInt();
        
        if (debugger != null) {
            return debugger.getScopes(frameId);
        }
        return new JsonObject();
    }
    
    private JsonObject handleGetVariables(JsonObject body) {
        int variablesReference = body.get("variablesReference").getAsInt();
        
        if (debugger != null) {
            return debugger.getVariables(variablesReference);
        }
        return new JsonObject();
    }
    
    private JsonObject handleEvaluate(JsonObject body) {
        int frameId = body.get("frameId").getAsInt();
        String expression = body.get("expression").getAsString();
        String context = body.has("context") ? body.get("context").getAsString() : "watch";
        
        if (debugger != null) {
            return debugger.evaluate(frameId, expression, context);
        }
        return new JsonObject();
    }
    
    private JsonObject handleSetVariable(JsonObject body) {
        int variablesReference = body.get("variablesReference").getAsInt();
        String name = body.get("name").getAsString();
        String value = body.get("value").getAsString();
        
        if (debugger != null) {
            return debugger.setVariable(variablesReference, name, value);
        }
        return new JsonObject();
    }
}

