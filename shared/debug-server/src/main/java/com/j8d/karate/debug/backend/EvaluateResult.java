package com.j8d.karate.debug.backend;

/**
 * Result of evaluating an expression.
 * 
 * @param value The display value of the result
 * @param type The type name of the result
 * @param variablesReference Reference ID to fetch child variables (0 if none)
 */
public record EvaluateResult(
    String value,
    String type,
    int variablesReference
) {
    /**
     * Creates a simple result with no children.
     */
    public static EvaluateResult simple(String value, String type) {
        return new EvaluateResult(value, type, 0);
    }
    
    /**
     * Creates a result with child variables.
     */
    public static EvaluateResult withChildren(String value, String type, int variablesReference) {
        return new EvaluateResult(value, type, variablesReference);
    }
    
    /**
     * Creates an error result.
     */
    public static EvaluateResult error(String message) {
        return new EvaluateResult(message, "error", 0);
    }
}

