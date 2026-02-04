package com.j8d.karate.debug;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Logback appender that sends log output as DAP output events.
 * This allows log messages to appear in the VS Code Debug Console with ANSI color support.
 * 
 * Color coding:
 * - Errors/Exceptions: Red
 * - Warnings: Yellow  
 * - Stopped/Breakpoint: Blue bold
 * - Success/Passed: Green bold
 * - Karate output ([print], [karate.log]): Green
 * - Debug info: Gray
 * - Normal: Default
 */
public class DapOutputAppender extends AppenderBase<ILoggingEvent> {

    private static final String KARATE_LOGGER = "com.intuit.karate";
    private static final String PRINT_PREFIX = "[print]";
    private static final String KARATE_LOG_PREFIX = "[karate.log] ";

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String GRAY = "\u001B[90m";
    private static final String BOLD = "\u001B[1m";

    private static volatile OutputEventSender outputSender;

    public static void setSession(OutputEventSender sender) {
        outputSender = sender;
    }

    public static void clearSession() {
        outputSender = null;
    }

    @Override
    protected void append(ILoggingEvent event) {
        OutputEventSender sender = outputSender;
        if (sender == null) {
            return;
        }

        // Filter out TRACE level logs - they create a feedback loop when sent to Debug Console
        // because sendMessage() logs at trace level, which triggers another send, and so on.
        // Debug Console is for user-visible output, not internal trace logs.
        if (event.getLevel() == Level.TRACE) {
            return;
        }

        String message = formatMessage(event);
        String coloredMessage = applyColor(message, event);
        String category = event.getLevel().isGreaterOrEqual(Level.ERROR) ? "stderr" : "stdout";

        sender.sendOutputEvent(category, coloredMessage);
    }

    private String formatMessage(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        String message = event.getFormattedMessage();

        // Add [karate.log] prefix for karate.log() calls (same logic as KarateLogEncoder)
        if (KARATE_LOGGER.equals(loggerName) && message != null
                && !message.startsWith(PRINT_PREFIX)
                && !isHttpLogMessage(message)) {
            return KARATE_LOG_PREFIX + message;
        }

        return message;
    }

    private String applyColor(String message, ILoggingEvent event) {
        if (message == null) {
            return "";
        }

        // Error level or error patterns - red
        if (event.getLevel().isGreaterOrEqual(Level.ERROR) ||
            message.contains("Exception") || message.contains("FAILED") || message.contains("failed:")) {
            return RED + message + RESET;
        }

        // Warning level - yellow
        if (event.getLevel() == Level.WARN || message.contains("WARN")) {
            return YELLOW + message + RESET;
        }

        // Stopped/breakpoint messages - blue bold
        if (message.startsWith("Stopped:")) {
            return BOLD + BLUE + message + RESET;
        }

        // Success patterns - green bold
        if (message.contains("passed:") || message.contains("PASSED")) {
            return BOLD + GREEN + message + RESET;
        }

        // Karate output - green
        if (message.startsWith("[print]") || message.startsWith("[karate.log]")) {
            return GREEN + message + RESET;
        }

        // Debug level - gray
        if (event.getLevel() == Level.DEBUG) {
            return GRAY + message + RESET;
        }

        return message;
    }

    private boolean isHttpLogMessage(String message) {
        if (message == null || message.length() < 4) {
            return false;
        }
        int i = 0;
        while (i < message.length() && Character.isDigit(message.charAt(i))) {
            i++;
        }
        if (i > 0 && i + 3 <= message.length()) {
            String rest = message.substring(i);
            return rest.startsWith(" > ") || rest.startsWith(" < ");
        }
        return false;
    }
}

