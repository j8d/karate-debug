package com.j8d.karate.debug.backend;

/**
 * Represents a variable or property for display in the debugger.
 * 
 * @param name The variable name
 * @param value The display value (formatted for human reading)
 * @param type The type name (e.g., "String", "Map", "int")
 * @param variablesReference Reference ID to fetch child variables (0 if none)
 * @param namedVariables Number of named child variables
 * @param indexedVariables Number of indexed child variables (for arrays)
 * @param evaluateName Expression to evaluate this variable (for modification)
 */
public record Variable(
    String name,
    String value,
    String type,
    int variablesReference,
    int namedVariables,
    int indexedVariables,
    String evaluateName
) {
    /**
     * Creates a simple variable with no children.
     */
    public static Variable simple(String name, String value, String type) {
        return new Variable(name, value, type, 0, 0, 0, name);
    }
    
    /**
     * Creates a variable with child variables (object/map).
     */
    public static Variable withChildren(String name, String value, String type, int variablesReference) {
        return new Variable(name, value, type, variablesReference, 0, 0, name);
    }
    
    /**
     * Creates a variable with known child counts.
     */
    public static Variable withCounts(String name, String value, String type, 
            int variablesReference, int namedVariables, int indexedVariables) {
        return new Variable(name, value, type, variablesReference, namedVariables, indexedVariables, name);
    }
    
    /**
     * Returns true if this variable has child variables.
     */
    public boolean hasChildren() {
        return variablesReference > 0;
    }
}

