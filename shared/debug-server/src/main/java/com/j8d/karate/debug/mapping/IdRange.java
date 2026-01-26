package com.j8d.karate.debug.mapping;

import com.j8d.karate.debug.backend.DebugBackend.BackendType;

/**
 * Defines ID ranges for each backend type.
 * 
 * This ensures IDs from different backends don't collide when presented
 * to the IDE as a unified debug session.
 * 
 * Ranges:
 * - Karate:     threads 1-999,     frames 1-99,999,       variables 1-999,999
 * - JavaScript: threads 1000-1999, frames 100,000-199,999, variables 1,000,000-1,999,999
 * - Java:       threads 2000-2999, frames 200,000-299,999, variables 2,000,000-2,999,999
 */
public enum IdRange {
    
    KARATE_THREADS(1, 999),
    KARATE_FRAMES(1, 99_999),
    KARATE_VARIABLES(1, 999_999),
    
    JAVASCRIPT_THREADS(1_000, 1_999),
    JAVASCRIPT_FRAMES(100_000, 199_999),
    JAVASCRIPT_VARIABLES(1_000_000, 1_999_999),
    
    JAVA_THREADS(2_000, 2_999),
    JAVA_FRAMES(200_000, 299_999),
    JAVA_VARIABLES(2_000_000, 2_999_999);
    
    private final int min;
    private final int max;
    
    IdRange(int min, int max) {
        this.min = min;
        this.max = max;
    }
    
    public int getMin() {
        return min;
    }
    
    public int getMax() {
        return max;
    }
    
    /**
     * Returns true if the given ID falls within this range.
     */
    public boolean contains(int id) {
        return id >= min && id <= max;
    }
    
    /**
     * Maps a backend-local ID to a global ID within this range.
     * 
     * @param localId The backend-local ID (typically starting from 1)
     * @return The global ID within this range
     * @throws IllegalArgumentException if the result would exceed the range
     */
    public int toGlobal(int localId) {
        int globalId = min + localId - 1;
        if (globalId > max) {
            throw new IllegalArgumentException(
                "ID " + localId + " exceeds range " + this.name() + " (max local: " + (max - min + 1) + ")");
        }
        return globalId;
    }
    
    /**
     * Maps a global ID back to a backend-local ID.
     * 
     * @param globalId The global ID within this range
     * @return The backend-local ID
     * @throws IllegalArgumentException if the ID is not in this range
     */
    public int toLocal(int globalId) {
        if (!contains(globalId)) {
            throw new IllegalArgumentException(
                "ID " + globalId + " is not in range " + this.name() + " (" + min + "-" + max + ")");
        }
        return globalId - min + 1;
    }
    
    // ========== Static helpers ==========
    
    /**
     * Gets the thread ID range for a backend type.
     */
    public static IdRange threadsFor(BackendType type) {
        return switch (type) {
            case KARATE -> KARATE_THREADS;
            case JAVASCRIPT -> JAVASCRIPT_THREADS;
            case JAVA -> JAVA_THREADS;
        };
    }
    
    /**
     * Gets the frame ID range for a backend type.
     */
    public static IdRange framesFor(BackendType type) {
        return switch (type) {
            case KARATE -> KARATE_FRAMES;
            case JAVASCRIPT -> JAVASCRIPT_FRAMES;
            case JAVA -> JAVA_FRAMES;
        };
    }
    
    /**
     * Gets the variables reference range for a backend type.
     */
    public static IdRange variablesFor(BackendType type) {
        return switch (type) {
            case KARATE -> KARATE_VARIABLES;
            case JAVASCRIPT -> JAVASCRIPT_VARIABLES;
            case JAVA -> JAVA_VARIABLES;
        };
    }
    
    /**
     * Determines which backend type a thread ID belongs to.
     */
    public static BackendType backendForThread(int threadId) {
        if (KARATE_THREADS.contains(threadId)) return BackendType.KARATE;
        if (JAVASCRIPT_THREADS.contains(threadId)) return BackendType.JAVASCRIPT;
        if (JAVA_THREADS.contains(threadId)) return BackendType.JAVA;
        throw new IllegalArgumentException("Unknown thread ID: " + threadId);
    }
    
    /**
     * Determines which backend type a frame ID belongs to.
     */
    public static BackendType backendForFrame(int frameId) {
        if (KARATE_FRAMES.contains(frameId)) return BackendType.KARATE;
        if (JAVASCRIPT_FRAMES.contains(frameId)) return BackendType.JAVASCRIPT;
        if (JAVA_FRAMES.contains(frameId)) return BackendType.JAVA;
        throw new IllegalArgumentException("Unknown frame ID: " + frameId);
    }
    
    /**
     * Determines which backend type a variables reference belongs to.
     */
    public static BackendType backendForVariables(int variablesReference) {
        if (KARATE_VARIABLES.contains(variablesReference)) return BackendType.KARATE;
        if (JAVASCRIPT_VARIABLES.contains(variablesReference)) return BackendType.JAVASCRIPT;
        if (JAVA_VARIABLES.contains(variablesReference)) return BackendType.JAVA;
        throw new IllegalArgumentException("Unknown variables reference: " + variablesReference);
    }
}

