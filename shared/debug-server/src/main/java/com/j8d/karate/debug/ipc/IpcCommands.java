package com.j8d.karate.debug.ipc;

/**
 * IPC command names sent from parent (coordinator) to child (runner).
 */
public final class IpcCommands {
    
    private IpcCommands() {} // Prevent instantiation
    
    // ========== Lifecycle ==========
    
    /** Request child to start Karate execution */
    public static final String START = "start";
    
    /** Request child to stop execution and exit */
    public static final String STOP = "stop";
    
    // ========== Breakpoints ==========
    
    /** Set breakpoints in a file (replaces existing) */
    public static final String SET_BREAKPOINTS = "setBreakpoints";
    
    // ========== Execution Control ==========
    
    /** Resume execution */
    public static final String RESUME = "resume";
    
    /** Step over current statement */
    public static final String STEP_OVER = "stepOver";
    
    /** Step into current statement */
    public static final String STEP_INTO = "stepInto";
    
    /** Step out of current function */
    public static final String STEP_OUT = "stepOut";
    
    /** Pause execution */
    public static final String PAUSE = "pause";
    
    // ========== Inspection ==========
    
    /** Get stack frames for a thread */
    public static final String GET_STACK_FRAMES = "getStackFrames";
    
    /** Get scopes for a frame */
    public static final String GET_SCOPES = "getScopes";
    
    /** Get variables for a scope/reference */
    public static final String GET_VARIABLES = "getVariables";
    
    /** Evaluate an expression */
    public static final String EVALUATE = "evaluate";
    
    /** Set a variable value */
    public static final String SET_VARIABLE = "setVariable";
}

