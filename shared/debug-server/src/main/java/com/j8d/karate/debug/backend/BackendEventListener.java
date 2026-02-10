package com.j8d.karate.debug.backend;

/**
 * Callback interface for debug backend events.
 * 
 * Backends use this interface to notify the multiplexer of state changes
 * (stopped, continued, terminated) and output. The multiplexer then
 * translates these into DAP events for the IDE.
 */
public interface BackendEventListener {
    
    /**
     * Called when execution has stopped (breakpoint hit, step completed, etc.).
     * 
     * @param backend The backend that stopped
     * @param threadId The thread ID (backend-local, will be mapped by multiplexer)
     * @param reason The stop reason: "breakpoint", "step", "pause", "exception", etc.
     * @param description Optional human-readable description (e.g., breakpoint condition)
     */
    void onStopped(DebugBackend backend, int threadId, String reason, String description);
    
    /**
     * Called when execution has continued after being stopped.
     * 
     * @param backend The backend that continued
     * @param threadId The thread ID that continued
     * @param allThreadsContinued True if all threads in this backend continued
     */
    void onContinued(DebugBackend backend, int threadId, boolean allThreadsContinued);
    
    /**
     * Called when the debug session has terminated.
     * 
     * @param backend The backend that terminated
     */
    void onTerminated(DebugBackend backend);
    
    /**
     * Called when the backend produces output (stdout, stderr, console messages).
     * 
     * @param backend The backend producing output
     * @param category The output category: "stdout", "stderr", "console"
     * @param text The output text
     */
    void onOutput(DebugBackend backend, String category, String text);
    
    /**
     * Called when a breakpoint has been validated/resolved.
     * This is useful for breakpoints that couldn't be verified immediately
     * (e.g., Java breakpoints for classes not yet loaded).
     *
     * @param backend The backend that resolved the breakpoint
     * @param breakpoint The resolved breakpoint with updated information
     */
    void onBreakpointResolved(DebugBackend backend, Breakpoint breakpoint);

    /**
     * Called when the Karate feature has completed execution.
     * Report generation will follow. This allows stopping auxiliary backends
     * (like JavaScript) to avoid slowing down report generation.
     *
     * @param backend The Karate backend that completed the feature
     */
    default void onFeatureComplete(DebugBackend backend) {
        // Default implementation does nothing
    }
}

