package com.j8d.karate.debug.backend;

/**
 * Represents a stack frame in the call stack.
 * 
 * @param id Unique identifier for this frame (backend-local, will be mapped)
 * @param name Display name for the frame (e.g., step text, method name)
 * @param sourcePath Absolute path to the source file
 * @param sourceName Short name of the source file
 * @param line 1-based line number in the source
 * @param column 1-based column number (0 if unknown)
 * @param presentationHint How to present this frame: "normal", "label", "subtle"
 */
public record StackFrame(
    int id,
    String name,
    String sourcePath,
    String sourceName,
    int line,
    int column,
    String presentationHint
) {
    /**
     * Creates a normal stack frame.
     */
    public static StackFrame of(int id, String name, String sourcePath, String sourceName, int line) {
        return new StackFrame(id, name, sourcePath, sourceName, line, 1, "normal");
    }
    
    /**
     * Creates a stack frame with column information.
     */
    public static StackFrame of(int id, String name, String sourcePath, String sourceName, int line, int column) {
        return new StackFrame(id, name, sourcePath, sourceName, line, column, "normal");
    }
    
    /**
     * Creates a label frame (used for grouping, not navigable).
     */
    public static StackFrame label(int id, String name) {
        return new StackFrame(id, name, null, null, 0, 0, "label");
    }
    
    /**
     * Creates a subtle frame (de-emphasized in UI, e.g., framework code).
     */
    public static StackFrame subtle(int id, String name, String sourcePath, String sourceName, int line) {
        return new StackFrame(id, name, sourcePath, sourceName, line, 1, "subtle");
    }
}

