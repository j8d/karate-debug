package com.j8d.karate.debug.cdp;

import com.google.gson.JsonObject;

/**
 * Listener interface for Chrome DevTools Protocol events.
 * 
 * CDP events are asynchronous notifications from the debugger about
 * state changes like script loading, breakpoint hits, and execution pauses.
 */
public interface CdpEventListener {
    
    /**
     * Called when a new script is parsed and available for debugging.
     * 
     * @param scriptId Unique identifier for the script
     * @param url The script's URL or file path
     * @param startLine Starting line number (0-based)
     * @param startColumn Starting column number (0-based)
     * @param endLine Ending line number (0-based)
     * @param endColumn Ending column number (0-based)
     * @param hash Script content hash
     */
    void onScriptParsed(String scriptId, String url, int startLine, int startColumn,
                        int endLine, int endColumn, String hash);
    
    /**
     * Called when execution is paused (breakpoint hit, step complete, etc.).
     * 
     * @param callFrames Array of call frames in the current stack
     * @param reason Why execution paused (breakpoint, step, exception, etc.)
     * @param hitBreakpoints Array of breakpoint IDs that were hit (may be empty)
     * @param data Additional data about the pause (e.g., exception details)
     */
    void onPaused(JsonObject[] callFrames, String reason, String[] hitBreakpoints, JsonObject data);
    
    /**
     * Called when execution resumes after being paused.
     */
    void onResumed();
    
    /**
     * Called when a breakpoint is resolved to an actual location.
     * This happens when a breakpoint is set and the debugger confirms
     * the exact location where it will trigger.
     * 
     * @param breakpointId The breakpoint's unique identifier
     * @param location The resolved location (scriptId, lineNumber, columnNumber)
     */
    void onBreakpointResolved(String breakpointId, JsonObject location);
    
    /**
     * Called when the debugger connection is closed.
     * 
     * @param code WebSocket close code
     * @param reason Close reason message
     * @param remote True if closed by remote end
     */
    void onDisconnected(int code, String reason, boolean remote);
    
    /**
     * Called when an error occurs in the CDP connection.
     * 
     * @param error The exception that occurred
     */
    void onError(Exception error);
}

