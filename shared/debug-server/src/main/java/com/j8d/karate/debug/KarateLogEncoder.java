package com.j8d.karate.debug;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.nio.charset.StandardCharsets;

/**
 * Custom logback encoder that adds [karate.log] prefix to karate.log() output.
 *
 * Karate's print statement outputs: [print] message
 * Karate's karate.log() outputs: message (no prefix)
 *
 * This encoder detects karate.log() output (from com.intuit.karate logger,
 * without [print] prefix) and adds [karate.log] prefix for consistency.
 *
 * We identify karate.log() calls by looking for messages that:
 * 1. Come from com.intuit.karate logger
 * 2. Don't start with common Karate prefixes like [print], 1 > (HTTP), etc.
 */
public class KarateLogEncoder extends PatternLayoutEncoder {

    private static final String KARATE_LOGGER = "com.intuit.karate";
    private static final String PRINT_PREFIX = "[print]";
    private static final String KARATE_LOG_PREFIX = "[karate.log] ";

    @Override
    public byte[] encode(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        String message = event.getFormattedMessage();

        // Check if this is a karate.log() call:
        // - From com.intuit.karate logger
        // - Not a [print] statement
        // - Not HTTP logging (those start with digits for the call depth like "1 > ", "1 < ")
        if (KARATE_LOGGER.equals(loggerName) && message != null
                && !message.startsWith(PRINT_PREFIX)
                && !isHttpLogMessage(message)) {
            // This is karate.log() output - add the prefix
            String prefixedMessage = KARATE_LOG_PREFIX + message + "\n";
            return prefixedMessage.getBytes(StandardCharsets.UTF_8);
        }

        // For all other messages, use the standard encoding
        return super.encode(event);
    }

    /**
     * Check if the message is HTTP logging from Karate.
     * HTTP log messages start with a number (call depth) followed by " > " or " < "
     * Examples: "1 > GET https://...", "1 < 200"
     */
    private boolean isHttpLogMessage(String message) {
        if (message == null || message.length() < 4) {
            return false;
        }
        // Check for pattern: digit(s) followed by " > " or " < "
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

