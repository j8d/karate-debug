package com.j8d.karate.debug.backend;

import java.util.List;

/**
 * Common interface for all debug backends (Karate, JavaScript, Java).
 * 
 * Each backend handles debugging for a specific language/runtime:
 * - KarateBackend: Karate DSL (.feature files) via IPC to child process
 * - JavaScriptBackend: JavaScript (.js files) via Chrome DevTools Protocol
 * - JavaBackend: Java (.java files) via Java Debug Interface (JDI)
 * 
 * The DapMultiplexer coordinates multiple backends and routes DAP messages
 * to the appropriate backend based on file type.
 */
public interface DebugBackend {
    
    /**
     * Returns the backend type identifier.
     */
    BackendType getType();
    
    // ========== Lifecycle ==========
    
    /**
     * Initializes the backend with an event listener.
     * Must be called before any other methods.
     * 
     * @param listener Callback for backend events
     */
    void initialize(BackendEventListener listener);
    
    /**
     * Starts the backend (connects to debug target, etc.).
     * This may be asynchronous - use isReady() to check status.
     */
    void start();
    
    /**
     * Stops the backend and releases resources.
     */
    void stop();
    
    /**
     * Returns true if the backend is ready to handle debug operations.
     */
    boolean isReady();
    
    // ========== Breakpoints ==========
    
    /**
     * Returns true if this backend handles the given file type.
     * 
     * @param filePath Absolute path to the source file
     * @return true if this backend should handle breakpoints in this file
     */
    boolean canHandleFile(String filePath);
    
    /**
     * Sets breakpoints in a source file.
     * Replaces any existing breakpoints in that file.
     * 
     * @param filePath Absolute path to the source file
     * @param breakpoints List of breakpoint requests
     * @return List of verified breakpoints (same order as requests)
     */
    List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> breakpoints);
    
    // ========== Execution Control ==========
    
    /**
     * Resumes execution of a stopped thread.
     * 
     * @param threadId The thread to resume (backend-local ID)
     */
    void resume(int threadId);
    
    /**
     * Steps over the current statement.
     * 
     * @param threadId The thread to step (backend-local ID)
     */
    void stepOver(int threadId);
    
    /**
     * Steps into the current statement.
     * 
     * @param threadId The thread to step (backend-local ID)
     */
    void stepInto(int threadId);
    
    /**
     * Steps out of the current function/method.
     * 
     * @param threadId The thread to step (backend-local ID)
     */
    void stepOut(int threadId);
    
    /**
     * Pauses execution of a running thread.
     * 
     * @param threadId The thread to pause (backend-local ID)
     */
    void pause(int threadId);
    
    // ========== Inspection ==========
    
    /**
     * Gets the stack frames for a stopped thread.
     * 
     * @param threadId The thread ID (backend-local)
     * @return List of stack frames, top of stack first
     */
    List<StackFrame> getStackFrames(int threadId);
    
    /**
     * Gets the scopes for a stack frame.
     * 
     * @param frameId The frame ID (backend-local)
     * @return List of scopes in this frame
     */
    List<Scope> getScopes(int frameId);
    
    /**
     * Gets the variables for a scope or structured variable.
     * 
     * @param variablesReference The reference ID from a Scope or Variable
     * @return List of variables
     */
    List<Variable> getVariables(int variablesReference);
    
    /**
     * Evaluates an expression in the context of a stack frame.
     * 
     * @param frameId The frame ID for context (backend-local)
     * @param expression The expression to evaluate
     * @param context The evaluation context: "watch", "repl", "hover"
     * @return The evaluation result
     */
    EvaluateResult evaluate(int frameId, String expression, String context);
    
    /**
     * Sets a variable value.
     * 
     * @param variablesReference The scope/container reference
     * @param name The variable name
     * @param value The new value (as a string to be parsed)
     * @return The result with the new display value
     */
    SetVariableResult setVariable(int variablesReference, String name, String value);
    
    // ========== Backend Type ==========
    
    /**
     * Enumeration of backend types for identification and ID mapping.
     */
    enum BackendType {
        KARATE,
        JAVASCRIPT,
        JAVA
    }
}

