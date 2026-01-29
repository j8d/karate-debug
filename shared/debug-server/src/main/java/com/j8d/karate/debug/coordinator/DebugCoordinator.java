package com.j8d.karate.debug.coordinator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.j8d.karate.debug.backend.Breakpoint;
import com.j8d.karate.debug.backend.BreakpointRequest;
import com.j8d.karate.debug.backend.EvaluateResult;
import com.j8d.karate.debug.backend.JavaBackend;
import com.j8d.karate.debug.backend.JavaScriptBackend;
import com.j8d.karate.debug.backend.KarateBackend;
import com.j8d.karate.debug.backend.SetVariableResult;
import com.j8d.karate.debug.multiplexer.DapMultiplexer;
import com.j8d.karate.debug.multiplexer.MultiplexerEventListener;
import com.j8d.karate.debug.process.ChildProcessConfig;
import com.j8d.karate.debug.process.ChildProcessInfo;
import com.j8d.karate.debug.process.ChildProcessManager;

/**
 * Orchestrates the unified polyglot debugging session.
 * 
 * Coordinates the startup sequence:
 * 1. Spawn child process with debug agents
 * 2. Wait for IPC ready event with port information
 * 3. Connect CDP client (if JS debugging enabled)
 * 4. Connect JDI client (if Java debugging enabled)
 * 5. Apply queued breakpoints
 * 6. Signal ready for execution
 * 
 * Manages state transitions and ensures proper timing between components.
 */
public class DebugCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DebugCoordinator.class);

    // Configuration
    private final ChildProcessConfig config;
    
    // Components
    private ChildProcessManager processManager;
    private DapMultiplexer multiplexer;
    private KarateBackend karateBackend;
    private JavaScriptBackend jsBackend;
    private JavaBackend javaBackend;
    
    // State
    private volatile CoordinatorState state = CoordinatorState.CREATED;
    private ChildProcessInfo processInfo;
    
    // Queued breakpoints (set before backends are ready)
    private final List<QueuedBreakpoints> queuedBreakpoints = new ArrayList<>();
    
    // Event listener
    private MultiplexerEventListener eventListener;
    
    /**
     * Coordinator states for the startup sequence.
     */
    public enum CoordinatorState {
        CREATED,           // Initial state
        CHILD_STARTING,    // Spawning child process
        CHILD_READY,       // Child process ready, IPC connected
        BACKENDS_CONNECTING, // Connecting CDP/JDI clients
        BACKENDS_READY,    // All backends connected
        BREAKPOINTS_SET,   // Breakpoints applied
        RUNNING,           // Execution started
        STOPPED,           // Stopped at breakpoint/step
        TERMINATED         // Session ended
    }
    
    /**
     * Record for queued breakpoint requests.
     */
    private record QueuedBreakpoints(String filePath, List<BreakpointRequest> requests) {}
    
    public DebugCoordinator(ChildProcessConfig config) {
        this.config = config;
    }
    
    /**
     * Sets the event listener for debug events.
     */
    public void setEventListener(MultiplexerEventListener listener) {
        this.eventListener = listener;
        if (multiplexer != null) {
            multiplexer.setEventListener(listener);
        }
    }
    
    /**
     * Returns the current coordinator state.
     */
    public CoordinatorState getState() {
        return state;
    }
    
    /**
     * Returns the multiplexer (available after initialize()).
     */
    public DapMultiplexer getMultiplexer() {
        return multiplexer;
    }
    
    /**
     * Initializes the coordinator and starts the child process.
     * This is an async operation - use the returned future to wait for completion.
     * 
     * @return Future that completes when backends are ready
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                doInitialize();
            } catch (Exception e) {
                log.error("Initialization failed", e);
                state = CoordinatorState.TERMINATED;
                throw new RuntimeException("Failed to initialize debug coordinator", e);
            }
        });
    }
    
    private void doInitialize() throws IOException, TimeoutException, InterruptedException {
        log.info("Initializing DebugCoordinator...");
        
        // Create multiplexer
        multiplexer = new DapMultiplexer();
        if (eventListener != null) {
            multiplexer.setEventListener(eventListener);
        }
        
        // Start child process
        state = CoordinatorState.CHILD_STARTING;
        processManager = new ChildProcessManager(config);
        processInfo = processManager.start();
        state = CoordinatorState.CHILD_READY;
        
        log.info("Child process ready: IPC={}, JDWP={}, CDP={}", 
            processInfo.getIpcPort(), processInfo.getJdwpPort(), processInfo.getCdpPort());
        
        // Create and register backends
        createBackends();
        
        // Connect backends with retry logic
        state = CoordinatorState.BACKENDS_CONNECTING;
        connectBackends();
        state = CoordinatorState.BACKENDS_READY;
        
        log.info("All backends ready");
    }

    /**
     * Creates and registers all backends with the multiplexer.
     */
    private void createBackends() {
        // Always create Karate backend (IPC-based)
        karateBackend = new KarateBackend(processManager);
        multiplexer.registerBackend(karateBackend);
        log.debug("Created KarateBackend");

        // Create JavaScript backend if CDP is available
        if (processInfo.hasJsDebugging()) {
            jsBackend = new JavaScriptBackend(processInfo.getCdpWebSocketUrl());
            multiplexer.registerBackend(jsBackend);
            log.debug("Created JavaScriptBackend for {}", processInfo.getCdpWebSocketUrl());
        }

        // Create Java backend if JDWP is available
        if (processInfo.hasJavaDebugging()) {
            javaBackend = new JavaBackend("localhost", processInfo.getJdwpPort());
            multiplexer.registerBackend(javaBackend);
            log.debug("Created JavaBackend for localhost:{}", processInfo.getJdwpPort());
        }
    }

    /**
     * Connects all backends with retry logic.
     * Backends may not be immediately available after child process starts.
     *
     * Note: Backend initialization is performed in {@link DapMultiplexer#registerBackend},
     * so this method intentionally avoids re-initializing backends to prevent
     * duplicate listener registration and other side effects.
     */
    private void connectBackends() throws IOException, TimeoutException, InterruptedException {
        // Backends are already initialized via registerBackend() in createBackends().
        // This method exists for any future connection/start logic that may be needed.
        log.debug("All backends registered and initialized");
    }

    // ========== Breakpoint Management ==========

    /**
     * Sets breakpoints for a file. Queues if backends not ready, applies immediately otherwise.
     */
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> requests) {
        if (state.ordinal() < CoordinatorState.BACKENDS_READY.ordinal()) {
            // Queue for later
            synchronized (queuedBreakpoints) {
                queuedBreakpoints.add(new QueuedBreakpoints(filePath, new ArrayList<>(requests)));
            }
            log.debug("Queued {} breakpoints for {} (state={})", requests.size(), filePath, state);
            return List.of(); // Return empty - will be set later
        }

        return multiplexer.setBreakpoints(filePath, requests);
    }

    /**
     * Applies all queued breakpoints. Called after backends are ready.
     */
    public void applyQueuedBreakpoints() {
        List<QueuedBreakpoints> toApply;
        synchronized (queuedBreakpoints) {
            toApply = new ArrayList<>(queuedBreakpoints);
            queuedBreakpoints.clear();
        }

        for (QueuedBreakpoints queued : toApply) {
            log.debug("Applying {} queued breakpoints for {}", queued.requests().size(), queued.filePath());
            multiplexer.setBreakpoints(queued.filePath(), queued.requests());
        }

        state = CoordinatorState.BREAKPOINTS_SET;
    }

    // ========== Lifecycle ==========

    /**
     * Starts execution after breakpoints are set.
     */
    public void start() {
        if (state != CoordinatorState.BACKENDS_READY && state != CoordinatorState.BREAKPOINTS_SET) {
            log.warn("Cannot start: invalid state {}", state);
            return;
        }

        // Apply any remaining queued breakpoints
        applyQueuedBreakpoints();

        // Start all backends
        multiplexer.start();
        state = CoordinatorState.RUNNING;
        log.info("Execution started");
    }

    /**
     * Stops the debug session and cleans up all resources.
     */
    public void stop() {
        log.info("Stopping DebugCoordinator");
        state = CoordinatorState.TERMINATED;

        // Stop multiplexer (which stops all backends)
        if (multiplexer != null) {
            multiplexer.stop();
        }

        // Stop child process
        if (processManager != null) {
            processManager.stop();
        }

        log.info("DebugCoordinator stopped");
    }

    // ========== Execution Control (delegated to multiplexer) ==========

    public void resume(int globalThreadId) {
        multiplexer.resume(globalThreadId);
        state = CoordinatorState.RUNNING;
    }

    public void stepOver(int globalThreadId) {
        multiplexer.stepOver(globalThreadId);
    }

    public void stepInto(int globalThreadId) {
        multiplexer.stepInto(globalThreadId);
    }

    public void stepOut(int globalThreadId) {
        multiplexer.stepOut(globalThreadId);
    }

    public void pause(int globalThreadId) {
        multiplexer.pause(globalThreadId);
    }

    // ========== Inspection (delegated to multiplexer) ==========

    public List<com.j8d.karate.debug.backend.StackFrame> getStackFrames(int globalThreadId) {
        return multiplexer.getStackFrames(globalThreadId);
    }

    public List<com.j8d.karate.debug.backend.StackFrame> getMergedStackFrames() {
        return multiplexer.getMergedStackFrames();
    }

    public List<com.j8d.karate.debug.backend.Scope> getScopes(int globalFrameId) {
        return multiplexer.getScopes(globalFrameId);
    }

    public List<com.j8d.karate.debug.backend.Variable> getVariables(int globalVariablesRef) {
        return multiplexer.getVariables(globalVariablesRef);
    }

    public EvaluateResult evaluate(int globalFrameId, String expression, String context) {
        return multiplexer.evaluate(globalFrameId, expression, context);
    }

    public SetVariableResult setVariable(int globalVariablesRef, String name, String value) {
        return multiplexer.setVariable(globalVariablesRef, name, value);
    }

    // ========== Event Handling ==========

    /**
     * Called when a backend stops (breakpoint hit, step complete, etc.).
     * Updates coordinator state.
     */
    public void onBackendStopped() {
        state = CoordinatorState.STOPPED;
    }
}
