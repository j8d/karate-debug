package com.j8d.karate.debug;

/**
 * Interface for sending DAP output events.
 * Implemented by both DapSession and PolyglotDapSession to allow
 * DapOutputAppender to send logs to the Debug Console in either mode.
 */
public interface OutputEventSender {
    
    /**
     * Send an output event to the IDE Debug Console.
     * @param category "stdout", "stderr", or "console"
     * @param text The output text (can include ANSI color codes)
     */
    void sendOutputEvent(String category, String text);
}

