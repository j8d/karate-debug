package com.j8d.karate.debug.multiplexer;

import com.j8d.karate.debug.backend.Breakpoint;

/**
 * Callback interface for multiplexer events.
 * 
 * These events use global IDs (already mapped from backend-local IDs)
 * and can be directly forwarded to the DAP session.
 */
public interface MultiplexerEventListener {
    
    /**
     * Called when execution has stopped.
     * 
     * @param globalThreadId The global thread ID
     * @param reason The stop reason: "breakpoint", "step", "pause", "exception", etc.
     * @param description Optional human-readable description
     */
    void onStopped(int globalThreadId, String reason, String description);
    
    /**
     * Called when execution has continued.
     * 
     * @param globalThreadId The global thread ID that continued
     * @param allThreadsContinued True if all threads continued
     */
    void onContinued(int globalThreadId, boolean allThreadsContinued);
    
    /**
     * Called when the debug session has terminated.
     */
    void onTerminated();
    
    /**
     * Called when output is produced.
     * 
     * @param category The output category: "stdout", "stderr", "console"
     * @param text The output text
     */
    void onOutput(String category, String text);
    
    /**
     * Called when a breakpoint has been resolved.
     * 
     * @param breakpoint The resolved breakpoint
     */
    void onBreakpointResolved(Breakpoint breakpoint);
}

