package com.j8d.karate.debug.ipc;

/**
 * IPC event names sent from child (runner) to parent (coordinator).
 */
public final class IpcEvents {
    
    private IpcEvents() {} // Prevent instantiation
    
    // ========== Lifecycle ==========
    
    /** Child is ready to receive commands */
    public static final String READY = "ready";
    
    /** Child has terminated */
    public static final String TERMINATED = "terminated";
    
    // ========== Execution State ==========
    
    /** Execution has stopped (breakpoint, step, etc.) */
    public static final String STOPPED = "stopped";
    
    /** Execution has continued */
    public static final String CONTINUED = "continued";

    /** Feature execution is complete (report generation will follow) */
    public static final String FEATURE_COMPLETE = "featureComplete";

    // ========== Output ==========
    
    /** Output from Karate execution (stdout, stderr, console) */
    public static final String OUTPUT = "output";
    
    // ========== Breakpoints ==========
    
    /** A breakpoint has been resolved/verified */
    public static final String BREAKPOINT_RESOLVED = "breakpointResolved";
}

