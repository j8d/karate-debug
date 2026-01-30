package com.j8d.karate.debug.dap;

import com.google.gson.JsonObject;

/**
 * Listener for DAP events from GraalVM's DAP server.
 */
public interface DapEventListener {
    
    /**
     * Called when execution stops (breakpoint, step, exception, etc.)
     * Body contains: reason, threadId, allThreadsStopped, etc.
     */
    void onStopped(JsonObject body);
    
    /**
     * Called when execution continues.
     * Body contains: threadId, allThreadsContinued
     */
    void onContinued(JsonObject body);
    
    /**
     * Called when the debuggee has terminated.
     */
    void onTerminated();
    
    /**
     * Called when output is produced.
     * Body contains: category, output, source, line, column, etc.
     */
    void onOutput(JsonObject body);
    
    /**
     * Called when a source file is loaded.
     * Body contains: reason, source
     */
    void onLoadedSource(JsonObject body);
    
    /**
     * Called when the connection is closed.
     */
    void onDisconnected(String reason);
    
    /**
     * Called when an error occurs.
     */
    void onError(Exception error);
}

