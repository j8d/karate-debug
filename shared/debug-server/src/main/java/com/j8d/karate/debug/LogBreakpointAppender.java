package com.j8d.karate.debug;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that monitors log output for log breakpoint patterns.
 * When a pattern matches, it notifies the debugger to pause at the next step.
 * 
 * This appender works alongside the normal console appender - it doesn't 
 * output anything itself, just monitors the log stream.
 */
public class LogBreakpointAppender extends AppenderBase<ILoggingEvent> {

    private static volatile KarateDebugger debugger;

    /**
     * Set the debugger instance to notify when a log breakpoint is triggered.
     * Called by DebugServer when starting a session.
     */
    public static void setDebugger(KarateDebugger debuggerInstance) {
        debugger = debuggerInstance;
    }

    /**
     * Clear the debugger reference when the session ends.
     */
    public static void clearDebugger() {
        debugger = null;
    }

    @Override
    protected void append(ILoggingEvent event) {
        KarateDebugger currentDebugger = debugger;
        if (currentDebugger == null) {
            return;
        }

        String message = event.getFormattedMessage();
        if (message != null) {
            currentDebugger.checkLogBreakpoint(message);
        }
    }
}

