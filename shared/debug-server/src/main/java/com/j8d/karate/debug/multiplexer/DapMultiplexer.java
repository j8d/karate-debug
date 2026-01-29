package com.j8d.karate.debug.multiplexer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.j8d.karate.debug.backend.BackendEventListener;
import com.j8d.karate.debug.backend.Breakpoint;
import com.j8d.karate.debug.backend.BreakpointRequest;
import com.j8d.karate.debug.backend.DebugBackend;
import com.j8d.karate.debug.backend.DebugBackend.BackendType;
import com.j8d.karate.debug.backend.JavaBackend;
import com.j8d.karate.debug.backend.EvaluateResult;
import com.j8d.karate.debug.backend.Scope;
import com.j8d.karate.debug.backend.SetVariableResult;
import com.j8d.karate.debug.backend.StackFrame;
import com.j8d.karate.debug.backend.Variable;
import com.j8d.karate.debug.mapping.IdRange;

/**
 * Central coordinator that manages multiple debug backends and routes DAP messages.
 * 
 * The DapMultiplexer:
 * - Registers and manages lifecycle of all backends (Karate, JavaScript, Java)
 * - Routes setBreakpoints to appropriate backend based on file extension
 * - Maps thread/frame/variable IDs between backends and DAP
 * - Aggregates events from all backends and forwards to DAP session
 * - Merges stack frames from multiple backends for cross-language calls
 */
public class DapMultiplexer implements BackendEventListener {

    private static final Logger log = LoggerFactory.getLogger(DapMultiplexer.class);

    // Registered backends by type
    private final Map<BackendType, DebugBackend> backends = new EnumMap<>(BackendType.class);
    
    // Listener for multiplexed events (typically the DAP session)
    private MultiplexerEventListener eventListener;
    
    // Track which backend currently has control (for thread operations)
    private final Map<Integer, BackendType> globalThreadToBackend = new ConcurrentHashMap<>();
    
    // Track breakpoints per file for cross-backend support
    private final Map<String, List<Breakpoint>> fileBreakpoints = new ConcurrentHashMap<>();
    
    // Current state
    private volatile boolean started = false;
    private volatile BackendType stoppedBackend = null;
    private volatile int stoppedThreadId = -1;

    // Track when we're stepping in a non-Karate backend
    // Used to suppress Karate's stopped event when Java/JS step completes
    private volatile BackendType steppingInBackend = null;

    // Track when we're in cross-language step mode (step from Karate into Java/JS)
    // This is different from just stepping within Java - in cross-language mode,
    // we want to suppress Karate's stopped event. When stepping within Java and
    // the step exits to framework code, we should NOT suppress Karate's event.
    private volatile boolean crossLanguageStepMode = false;
    
    /**
     * Creates a new DapMultiplexer.
     */
    public DapMultiplexer() {
    }
    
    // ========== Backend Registration ==========
    
    /**
     * Registers a backend with the multiplexer.
     * The backend's initialize() method will be called with this multiplexer as the listener.
     */
    public void registerBackend(DebugBackend backend) {
        BackendType type = backend.getType();
        if (backends.containsKey(type)) {
            throw new IllegalStateException("Backend already registered for type: " + type);
        }
        
        log.info("Registering backend: {}", type);
        backends.put(type, backend);
        backend.initialize(this);
    }
    
    /**
     * Returns a registered backend by type, or null if not registered.
     */
    public DebugBackend getBackend(BackendType type) {
        return backends.get(type);
    }
    
    /**
     * Returns all registered backends.
     */
    public List<DebugBackend> getAllBackends() {
        return new ArrayList<>(backends.values());
    }
    
    /**
     * Sets the event listener for multiplexed events.
     */
    public void setEventListener(MultiplexerEventListener listener) {
        this.eventListener = listener;
    }
    
    // ========== Lifecycle ==========
    
    /**
     * Starts all registered backends.
     * Backends are started in order: Karate, then JavaScript, then Java.
     */
    public void start() {
        if (started) {
            log.warn("DapMultiplexer already started");
            return;
        }
        
        log.info("Starting DapMultiplexer with {} backends", backends.size());
        
        // Start backends in order for proper coordination
        for (BackendType type : BackendType.values()) {
            DebugBackend backend = backends.get(type);
            if (backend != null) {
                log.info("Starting backend: {}", type);
                backend.start();
            }
        }
        
        started = true;
    }
    
    /**
     * Stops all registered backends.
     */
    public void stop() {
        if (!started) {
            return;
        }
        
        log.info("Stopping DapMultiplexer");
        
        // Stop backends in reverse order
        BackendType[] types = BackendType.values();
        for (int i = types.length - 1; i >= 0; i--) {
            DebugBackend backend = backends.get(types[i]);
            if (backend != null) {
                log.info("Stopping backend: {}", types[i]);
                backend.stop();
            }
        }
        
        started = false;
        globalThreadToBackend.clear();
        fileBreakpoints.clear();
    }
    
    /**
     * Returns true if all registered backends are ready.
     */
    public boolean isReady() {
        for (DebugBackend backend : backends.values()) {
            if (!backend.isReady()) {
                return false;
            }
        }
        return started && !backends.isEmpty();
    }

    // ========== File-Based Routing ==========

    /**
     * Determines which backend should handle a file based on extension.
     */
    public BackendType getBackendTypeForFile(String filePath) {
        if (filePath == null) {
            return BackendType.KARATE;
        }

        String lower = filePath.toLowerCase();
        if (lower.endsWith(".feature")) {
            return BackendType.KARATE;
        } else if (lower.endsWith(".js")) {
            return BackendType.JAVASCRIPT;
        } else if (lower.endsWith(".java")) {
            return BackendType.JAVA;
        }

        // Default to Karate for unknown files
        return BackendType.KARATE;
    }

    /**
     * Routes setBreakpoints to the appropriate backend based on file extension.
     *
     * @param filePath The source file path
     * @param breakpoints The breakpoint requests
     * @return List of verified breakpoints with global IDs
     */
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> breakpoints) {
        BackendType type = getBackendTypeForFile(filePath);
        DebugBackend backend = backends.get(type);

        if (backend == null) {
            log.warn("No backend registered for file type: {} (file: {})", type, filePath);
            // Return unverified breakpoints
            List<Breakpoint> result = new ArrayList<>();
            for (int i = 0; i < breakpoints.size(); i++) {
                result.add(Breakpoint.unverified(i + 1, breakpoints.get(i).line(), filePath,
                    "No backend for file type"));
            }
            return result;
        }

        if (!backend.canHandleFile(filePath)) {
            log.warn("Backend {} cannot handle file: {}", type, filePath);
            List<Breakpoint> result = new ArrayList<>();
            for (int i = 0; i < breakpoints.size(); i++) {
                result.add(Breakpoint.unverified(i + 1, breakpoints.get(i).line(), filePath,
                    "Backend cannot handle this file"));
            }
            return result;
        }

        log.debug("Routing setBreakpoints to {}: {} breakpoints in {}",
            type, breakpoints.size(), filePath);

        List<Breakpoint> result = backend.setBreakpoints(filePath, breakpoints);
        fileBreakpoints.put(filePath, result);
        return result;
    }

    // ========== ID Mapping ==========

    /**
     * Maps a backend-local thread ID to a global thread ID.
     */
    public int toGlobalThreadId(BackendType type, int localThreadId) {
        int globalId = IdRange.threadsFor(type).toGlobal(localThreadId);
        globalThreadToBackend.put(globalId, type);
        return globalId;
    }

    /**
     * Maps a global thread ID back to a backend-local thread ID.
     * @return A record containing the backend type and local thread ID
     */
    public ThreadRef toLocalThreadId(int globalThreadId) {
        BackendType type = IdRange.backendForThread(globalThreadId);
        int localId = IdRange.threadsFor(type).toLocal(globalThreadId);
        return new ThreadRef(type, localId);
    }

    /**
     * Maps a backend-local frame ID to a global frame ID.
     */
    public int toGlobalFrameId(BackendType type, int localFrameId) {
        return IdRange.framesFor(type).toGlobal(localFrameId);
    }

    /**
     * Maps a global frame ID back to a backend-local frame ID.
     */
    public FrameRef toLocalFrameId(int globalFrameId) {
        BackendType type = IdRange.backendForFrame(globalFrameId);
        int localId = IdRange.framesFor(type).toLocal(globalFrameId);
        return new FrameRef(type, localId);
    }

    /**
     * Maps a backend-local variables reference to a global reference.
     */
    public int toGlobalVariablesRef(BackendType type, int localRef) {
        if (localRef == 0) {
            return 0; // 0 means no children
        }
        return IdRange.variablesFor(type).toGlobal(localRef);
    }

    /**
     * Maps a global variables reference back to a backend-local reference.
     */
    public VariablesRef toLocalVariablesRef(int globalRef) {
        if (globalRef == 0) {
            return new VariablesRef(null, 0);
        }
        BackendType type = IdRange.backendForVariables(globalRef);
        int localId = IdRange.variablesFor(type).toLocal(globalRef);
        return new VariablesRef(type, localId);
    }

    // Reference records for ID mapping results
    public record ThreadRef(BackendType type, int localId) {}
    public record FrameRef(BackendType type, int localId) {}
    public record VariablesRef(BackendType type, int localId) {}

    // ========== Execution Control ==========

    /**
     * Resumes execution on the specified global thread.
     */
    public void resume(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);
        DebugBackend backend = backends.get(ref.type());
        if (backend != null) {
            log.debug("Resume on {} thread {}", ref.type(), ref.localId());
            backend.resume(ref.localId());
        }
    }

    /**
     * Steps over on the specified global thread.
     * If Karate is blocked (stopped in Java/JS), redirects to the stopped backend.
     */
    public void stepOver(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);

        // If requesting step on Karate but we're stopped in Java/JS, redirect to stopped backend
        if (ref.type() == BackendType.KARATE && stoppedBackend != null && stoppedBackend != BackendType.KARATE) {
            log.debug("Redirecting step over from Karate to stopped backend {}", stoppedBackend);
            DebugBackend stoppedBackendInstance = backends.get(stoppedBackend);
            if (stoppedBackendInstance != null) {
                int localThreadId = IdRange.threadsFor(stoppedBackend).toLocal(stoppedThreadId);
                steppingInBackend = stoppedBackend; // Track that we're stepping in this backend
                stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
                stoppedBackendInstance.stepOver(localThreadId);
                return;
            }
        }

        DebugBackend backend = backends.get(ref.type());
        if (backend != null) {
            log.debug("Step over on {} thread {}", ref.type(), ref.localId());
            if (ref.type() != BackendType.KARATE) {
                steppingInBackend = ref.type(); // Track that we're stepping in this backend
            }
            stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
            backend.stepOver(ref.localId());
        }
    }

    /**
     * Steps into on the specified global thread.
     * For cross-language step-into from Karate, enables Java method entry catching.
     */
    public void stepInto(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);

        // If requesting step on Karate but we're stopped in Java/JS, redirect to stopped backend
        // Karate is blocked waiting for the Java/JS call to complete
        if (ref.type() == BackendType.KARATE && stoppedBackend != null && stoppedBackend != BackendType.KARATE) {
            log.debug("Redirecting step from Karate to stopped backend {}", stoppedBackend);
            DebugBackend stoppedBackendInstance = backends.get(stoppedBackend);
            if (stoppedBackendInstance != null) {
                int localThreadId = IdRange.threadsFor(stoppedBackend).toLocal(stoppedThreadId);
                steppingInBackend = stoppedBackend; // Track that we're stepping in this backend
                stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
                stoppedBackendInstance.stepInto(localThreadId);
                return;
            }
        }

        DebugBackend backend = backends.get(ref.type());

        if (backend == null) {
            log.warn("No backend for thread {}", globalThreadId);
            return;
        }

        // If stepping from Karate and Java backend exists, enable method entry catching
        // This allows us to catch when Karate calls into user Java code
        if (ref.type() == BackendType.KARATE && backends.containsKey(BackendType.JAVA)) {
            JavaBackend javaBackend = (JavaBackend) backends.get(BackendType.JAVA);
            javaBackend.enableMethodEntry();
            // Set steppingInBackend to JAVA so we suppress Karate's stopped event
            // if Java method entry catches before Karate step completes
            steppingInBackend = BackendType.JAVA;
            crossLanguageStepMode = true; // This is a cross-language step from Karate
            log.debug("Enabled Java method entry for cross-language step-into");
        } else if (ref.type() != BackendType.KARATE) {
            steppingInBackend = ref.type(); // Track that we're stepping in this backend
            // NOT cross-language mode - this is stepping within Java/JS
            crossLanguageStepMode = false;
        }

        stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
        log.debug("Step into on {} thread {}", ref.type(), ref.localId());
        backend.stepInto(ref.localId());
    }

    /**
     * Steps out on the specified global thread.
     * If Karate is blocked (stopped in Java/JS), redirects to the stopped backend.
     */
    public void stepOut(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);

        // If requesting step on Karate but we're stopped in Java/JS, redirect to stopped backend
        if (ref.type() == BackendType.KARATE && stoppedBackend != null && stoppedBackend != BackendType.KARATE) {
            log.debug("Redirecting step out from Karate to stopped backend {}", stoppedBackend);
            DebugBackend stoppedBackendInstance = backends.get(stoppedBackend);
            if (stoppedBackendInstance != null) {
                int localThreadId = IdRange.threadsFor(stoppedBackend).toLocal(stoppedThreadId);
                steppingInBackend = stoppedBackend; // Track that we're stepping in this backend
                stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
                stoppedBackendInstance.stepOut(localThreadId);
                return;
            }
        }

        DebugBackend backend = backends.get(ref.type());
        if (backend != null) {
            log.debug("Step out on {} thread {}", ref.type(), ref.localId());
            if (ref.type() != BackendType.KARATE) {
                steppingInBackend = ref.type(); // Track that we're stepping in this backend
            }
            stoppedBackend = null; // Clear so next stopped event is not suppressed as duplicate
            backend.stepOut(ref.localId());
        }
    }

    /**
     * Pauses execution on the specified global thread.
     */
    public void pause(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);
        DebugBackend backend = backends.get(ref.type());
        if (backend != null) {
            log.debug("Pause on {} thread {}", ref.type(), ref.localId());
            backend.pause(ref.localId());
        }
    }

    // ========== Inspection ==========

    /**
     * Gets stack frames for a global thread ID.
     * Maps local frame IDs to global IDs in the returned frames.
     *
     * When stopped in a non-Karate backend (Java/JS), Karate is blocked waiting
     * for the call to complete. In this case, we return empty frames for Karate
     * to avoid timeout errors.
     */
    public List<StackFrame> getStackFrames(int globalThreadId) {
        ThreadRef ref = toLocalThreadId(globalThreadId);
        log.debug("getStackFrames: globalThreadId={}, backend={}, localId={}", globalThreadId, ref.type(), ref.localId());
        DebugBackend backend = backends.get(ref.type());
        if (backend == null) {
            log.debug("getStackFrames: no backend for type {}", ref.type());
            return List.of();
        }

        // If requesting Karate frames but we're stopped in Java/JS, Karate is blocked
        // and can't respond to IPC. Return empty frames to avoid timeout.
        if (ref.type() == BackendType.KARATE && stoppedBackend != null && stoppedBackend != BackendType.KARATE) {
            log.debug("Karate is blocked (stopped in {}), returning empty frames for thread {}", stoppedBackend, globalThreadId);
            return List.of();
        }

        List<StackFrame> localFrames = backend.getStackFrames(ref.localId());
        log.debug("getStackFrames: got {} frames from backend {}", localFrames.size(), ref.type());
        for (StackFrame frame : localFrames) {
            log.debug("  Frame: id={}, name={}, source={}, line={}", frame.id(), frame.name(), frame.sourcePath(), frame.line());
        }
        return mapFramesToGlobal(ref.type(), localFrames);
    }

    /**
     * Gets merged stack frames from all stopped backends.
     * This is useful for cross-language call stacks where Karate calls JavaScript or Java.
     *
     * Frames are ordered by backend priority: KARATE first, then JAVASCRIPT, then JAVA.
     * This reflects the typical call order (Karate triggers JS/Java execution).
     *
     * @return Combined list of stack frames from all backends with global IDs
     */
    public List<StackFrame> getMergedStackFrames() {
        List<StackFrame> allFrames = new ArrayList<>();

        // Collect frames from all ready backends
        // Order: Karate (highest level) -> JavaScript -> Java (lowest level)
        for (BackendType type : BackendType.values()) {
            DebugBackend backend = backends.get(type);
            if (backend != null && backend.isReady()) {
                try {
                    // Use thread 1 for each backend (main thread)
                    List<StackFrame> frames = backend.getStackFrames(1);
                    if (!frames.isEmpty()) {
                        allFrames.addAll(mapFramesToGlobal(type, frames));
                    }
                } catch (Exception e) {
                    log.debug("Could not get frames from {}: {}", type, e.getMessage());
                }
            }
        }

        return allFrames;
    }

    /**
     * Helper to map local frames to global IDs.
     */
    private List<StackFrame> mapFramesToGlobal(BackendType type, List<StackFrame> localFrames) {
        List<StackFrame> globalFrames = new ArrayList<>();

        for (StackFrame local : localFrames) {
            int globalFrameId = toGlobalFrameId(type, local.id());
            globalFrames.add(StackFrame.of(
                globalFrameId,
                local.name(),
                local.sourcePath(),
                local.sourceName(),
                local.line(),
                local.column()
            ));
        }

        return globalFrames;
    }

    /**
     * Gets scopes for a global frame ID.
     * Maps local variable references to global references.
     */
    public List<Scope> getScopes(int globalFrameId) {
        FrameRef ref = toLocalFrameId(globalFrameId);
        DebugBackend backend = backends.get(ref.type());
        if (backend == null) {
            return List.of();
        }

        List<Scope> localScopes = backend.getScopes(ref.localId());
        List<Scope> globalScopes = new ArrayList<>();

        for (Scope local : localScopes) {
            int globalRef = toGlobalVariablesRef(ref.type(), local.variablesReference());
            globalScopes.add(Scope.of(local.name(), globalRef));
        }

        return globalScopes;
    }

    /**
     * Gets variables for a global variables reference.
     * Maps child variable references to global references.
     */
    public List<Variable> getVariables(int globalVariablesRef) {
        VariablesRef ref = toLocalVariablesRef(globalVariablesRef);
        if (ref.type() == null) {
            return List.of();
        }

        DebugBackend backend = backends.get(ref.type());
        if (backend == null) {
            return List.of();
        }

        List<Variable> localVars = backend.getVariables(ref.localId());
        List<Variable> globalVars = new ArrayList<>();

        for (Variable local : localVars) {
            int globalChildRef = toGlobalVariablesRef(ref.type(), local.variablesReference());
            globalVars.add(Variable.withChildren(
                local.name(),
                local.value(),
                local.type(),
                globalChildRef
            ));
        }

        return globalVars;
    }

    /**
     * Evaluates an expression in the context of a global frame ID.
     */
    public EvaluateResult evaluate(int globalFrameId, String expression, String context) {
        // Handle frame ID 0 (used by VS Code for watch expressions without specific frame context)
        // Default to the currently stopped backend, or Karate if none stopped
        FrameRef ref;
        if (globalFrameId == 0) {
            BackendType type = stoppedBackend != null ? stoppedBackend : BackendType.KARATE;
            ref = new FrameRef(type, 0);
            log.debug("Evaluate with frameId=0, stoppedBackend={}, using backend {}", stoppedBackend, type);
        } else {
            ref = toLocalFrameId(globalFrameId);
        }

        DebugBackend backend = backends.get(ref.type());
        if (backend == null) {
            return EvaluateResult.error("No backend for frame");
        }

        EvaluateResult result = backend.evaluate(ref.localId(), expression, context);

        // Map variablesReference if present
        if (result.variablesReference() > 0) {
            int globalRef = toGlobalVariablesRef(ref.type(), result.variablesReference());
            return EvaluateResult.withChildren(result.value(), result.type(), globalRef);
        }

        return result;
    }

    /**
     * Sets a variable value using global variables reference.
     */
    public SetVariableResult setVariable(int globalVariablesRef, String name, String value) {
        VariablesRef ref = toLocalVariablesRef(globalVariablesRef);
        if (ref.type() == null) {
            return SetVariableResult.simple("Error: invalid reference", "error");
        }

        DebugBackend backend = backends.get(ref.type());
        if (backend == null) {
            return SetVariableResult.simple("Error: no backend", "error");
        }

        SetVariableResult result = backend.setVariable(ref.localId(), name, value);

        // Map variablesReference if present
        if (result.variablesReference() > 0) {
            int globalRef = toGlobalVariablesRef(ref.type(), result.variablesReference());
            return SetVariableResult.withChildren(result.value(), result.type(), globalRef);
        }

        return result;
    }

    // ========== BackendEventListener Implementation ==========

    @Override
    public void onStopped(DebugBackend backend, int localThreadId, String reason, String description) {
        BackendType type = backend.getType();
        int globalThreadId = toGlobalThreadId(type, localThreadId);

        log.debug("Backend {} stopped: thread={}, reason={}, steppingInBackend={}, stoppedBackend={}",
                  type, globalThreadId, reason, steppingInBackend, stoppedBackend);

        // If Karate reports stopped while we're waiting for Java (cross-language step mode),
        // it means the call completed WITHOUT entering user Java code. This happens when:
        // 1. The call was to JavaScript (GraalJS doesn't trigger Java method entry)
        // 2. The call was to framework-only Java code (filtered out by method entry filter)
        // In either case, forward the Karate stopped event and clean up.
        if (type == BackendType.KARATE && steppingInBackend != null && steppingInBackend != BackendType.KARATE) {
            log.debug("Karate stopped while waiting for {} - call did not enter user {} code, forwarding Karate event",
                      steppingInBackend, steppingInBackend);
            steppingInBackend = null;
            crossLanguageStepMode = false;

            // Disable method entry catching and cancel any pending steps
            if (backends.containsKey(BackendType.JAVA)) {
                JavaBackend javaBackend = (JavaBackend) backends.get(BackendType.JAVA);
                javaBackend.disableMethodEntry();
                javaBackend.cancelAllSteps();
            }
            // Fall through to forward the Karate stopped event
        }

        // If we're already stopped in this backend, suppress duplicate stopped events
        // This can happen when both method entry and breakpoint fire at the same location
        if (stoppedBackend == type) {
            log.debug("Suppressing duplicate stopped event for backend {}", type);
            return;
        }

        // Clear the stepping flag when the target backend stops
        if (type == steppingInBackend) {
            steppingInBackend = null;
            crossLanguageStepMode = false; // Cross-language step completed
        }

        // Disable method entry catching if it was enabled (cross-language step cleanup)
        if (backends.containsKey(BackendType.JAVA)) {
            JavaBackend javaBackend = (JavaBackend) backends.get(BackendType.JAVA);
            javaBackend.disableMethodEntry();
        }

        stoppedBackend = type;
        stoppedThreadId = globalThreadId;

        if (eventListener != null) {
            log.info("Forwarding stopped event to VS Code: thread={}, reason={}, backend={}",
                     globalThreadId, reason, type);
            eventListener.onStopped(globalThreadId, reason, description);
        }
    }

    @Override
    public void onContinued(DebugBackend backend, int localThreadId, boolean allThreadsContinued) {
        BackendType type = backend.getType();
        int globalThreadId = toGlobalThreadId(type, localThreadId);

        log.debug("Backend {} continued: thread={}", type, globalThreadId);

        if (stoppedBackend == type) {
            stoppedBackend = null;
            stoppedThreadId = -1;
        }

        if (eventListener != null) {
            eventListener.onContinued(globalThreadId, allThreadsContinued);
        }
    }

    @Override
    public void onTerminated(DebugBackend backend) {
        BackendType type = backend.getType();
        log.info("Backend {} terminated", type);

        // Check if all backends have terminated
        boolean allTerminated = true;
        for (DebugBackend b : backends.values()) {
            if (b.isReady()) {
                allTerminated = false;
                break;
            }
        }

        if (allTerminated && eventListener != null) {
            eventListener.onTerminated();
        }
    }

    @Override
    public void onOutput(DebugBackend backend, String category, String text) {
        if (eventListener != null) {
            eventListener.onOutput(category, text);
        }
    }

    @Override
    public void onBreakpointResolved(DebugBackend backend, Breakpoint breakpoint) {
        if (eventListener != null) {
            eventListener.onBreakpointResolved(breakpoint);
        }
    }

    // ========== State Accessors ==========

    /**
     * Returns the backend type that is currently stopped, or null if not stopped.
     */
    public BackendType getStoppedBackend() {
        return stoppedBackend;
    }

    /**
     * Returns the global thread ID that is currently stopped, or -1 if not stopped.
     */
    public int getStoppedThreadId() {
        return stoppedThreadId;
    }
}

