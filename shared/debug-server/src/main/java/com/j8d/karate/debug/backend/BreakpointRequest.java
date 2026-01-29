package com.j8d.karate.debug.backend;

/**
 * A request to set a breakpoint at a specific line.
 * 
 * @param line The 1-based line number
 * @param condition Optional condition expression (null for unconditional breakpoints)
 * @param hitCondition Optional hit count condition (e.g., ">= 5")
 * @param logMessage Optional log message to output instead of stopping (logpoint)
 */
public record BreakpointRequest(
    int line,
    String condition,
    String hitCondition,
    String logMessage
) {
    /**
     * Creates an unconditional breakpoint request.
     */
    public static BreakpointRequest at(int line) {
        return new BreakpointRequest(line, null, null, null);
    }
    
    /**
     * Creates a conditional breakpoint request.
     */
    public static BreakpointRequest conditional(int line, String condition) {
        return new BreakpointRequest(line, condition, null, null);
    }
    
    /**
     * Returns true if this breakpoint has a condition.
     */
    public boolean hasCondition() {
        return condition != null && !condition.isEmpty();
    }
    
    /**
     * Returns true if this is a logpoint (logs message instead of stopping).
     */
    public boolean isLogpoint() {
        return logMessage != null && !logMessage.isEmpty();
    }
}

