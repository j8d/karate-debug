package com.j8d.karate.debug.backend;

/**
 * Represents a variable scope within a stack frame.
 * 
 * @param name Display name for the scope (e.g., "Variables", "Locals", "this")
 * @param variablesReference Reference ID to fetch variables in this scope
 * @param namedVariables Number of named variables (for UI hints)
 * @param indexedVariables Number of indexed variables (for arrays)
 * @param expensive True if fetching variables is expensive (lazy load in UI)
 */
public record Scope(
    String name,
    int variablesReference,
    int namedVariables,
    int indexedVariables,
    boolean expensive
) {
    /**
     * Creates a simple scope with unknown variable count.
     */
    public static Scope of(String name, int variablesReference) {
        return new Scope(name, variablesReference, 0, 0, false);
    }
    
    /**
     * Creates a scope with known named variable count.
     */
    public static Scope withCount(String name, int variablesReference, int namedVariables) {
        return new Scope(name, variablesReference, namedVariables, 0, false);
    }
    
    /**
     * Creates an expensive scope (will be lazy-loaded in UI).
     */
    public static Scope expensive(String name, int variablesReference) {
        return new Scope(name, variablesReference, 0, 0, true);
    }
}

