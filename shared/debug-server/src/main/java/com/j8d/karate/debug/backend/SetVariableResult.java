package com.j8d.karate.debug.backend;

/**
 * Result of setting a variable value.
 * 
 * @param value The new display value
 * @param type The type name of the new value
 * @param variablesReference Reference ID if the new value has children (0 if none)
 */
public record SetVariableResult(
    String value,
    String type,
    int variablesReference
) {
    /**
     * Creates a simple result with no children.
     */
    public static SetVariableResult simple(String value, String type) {
        return new SetVariableResult(value, type, 0);
    }
    
    /**
     * Creates a result with child variables.
     */
    public static SetVariableResult withChildren(String value, String type, int variablesReference) {
        return new SetVariableResult(value, type, variablesReference);
    }
}

