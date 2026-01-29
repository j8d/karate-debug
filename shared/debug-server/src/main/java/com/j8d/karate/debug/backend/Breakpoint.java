package com.j8d.karate.debug.backend;

/**
 * Represents a verified breakpoint returned by a backend.
 * 
 * @param id Unique identifier for this breakpoint (backend-local)
 * @param verified True if the breakpoint could be set at the requested location
 * @param line The actual line where the breakpoint was set (may differ from requested)
 * @param source The source file path
 * @param message Optional message explaining why breakpoint couldn't be verified
 */
public record Breakpoint(
    int id,
    boolean verified,
    int line,
    String source,
    String message
) {
    /**
     * Creates a verified breakpoint.
     */
    public static Breakpoint verified(int id, int line, String source) {
        return new Breakpoint(id, true, line, source, null);
    }
    
    /**
     * Creates an unverified breakpoint with an explanation message.
     */
    public static Breakpoint unverified(int id, int line, String source, String message) {
        return new Breakpoint(id, false, line, source, message);
    }
    
    /**
     * Creates a pending breakpoint (will be verified later, e.g., when class loads).
     */
    public static Breakpoint pending(int id, int line, String source) {
        return new Breakpoint(id, false, line, source, "Pending - will be set when code loads");
    }
}

