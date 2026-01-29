package com.j8d.karate.debug.mapping;

import java.util.List;
import java.util.stream.Collectors;

import com.j8d.karate.debug.backend.Breakpoint;
import com.j8d.karate.debug.backend.DebugBackend;
import com.j8d.karate.debug.backend.DebugBackend.BackendType;
import com.j8d.karate.debug.backend.Scope;
import com.j8d.karate.debug.backend.StackFrame;
import com.j8d.karate.debug.backend.Variable;

/**
 * Maps IDs between backend-local and global (DAP) namespaces.
 * 
 * Each backend uses its own ID space starting from 1. The IdMapper
 * translates these to non-overlapping global IDs for the DAP session,
 * and back again when routing requests to backends.
 */
public class IdMapper {
    
    // ========== Thread ID Mapping ==========
    
    /**
     * Maps a backend-local thread ID to a global thread ID.
     */
    public int mapThreadId(BackendType type, int localThreadId) {
        return IdRange.threadsFor(type).toGlobal(localThreadId);
    }
    
    /**
     * Maps a global thread ID back to a backend-local thread ID.
     */
    public int unmapThreadId(int globalThreadId) {
        BackendType type = IdRange.backendForThread(globalThreadId);
        return IdRange.threadsFor(type).toLocal(globalThreadId);
    }
    
    /**
     * Gets the backend type for a global thread ID.
     */
    public BackendType getBackendForThread(int globalThreadId) {
        return IdRange.backendForThread(globalThreadId);
    }
    
    // ========== Frame ID Mapping ==========
    
    /**
     * Maps a backend-local frame ID to a global frame ID.
     */
    public int mapFrameId(BackendType type, int localFrameId) {
        return IdRange.framesFor(type).toGlobal(localFrameId);
    }
    
    /**
     * Maps a global frame ID back to a backend-local frame ID.
     */
    public int unmapFrameId(int globalFrameId) {
        BackendType type = IdRange.backendForFrame(globalFrameId);
        return IdRange.framesFor(type).toLocal(globalFrameId);
    }
    
    /**
     * Gets the backend type for a global frame ID.
     */
    public BackendType getBackendForFrame(int globalFrameId) {
        return IdRange.backendForFrame(globalFrameId);
    }
    
    // ========== Variables Reference Mapping ==========
    
    /**
     * Maps a backend-local variables reference to a global reference.
     */
    public int mapVariablesReference(BackendType type, int localRef) {
        if (localRef == 0) return 0; // 0 means no children
        return IdRange.variablesFor(type).toGlobal(localRef);
    }
    
    /**
     * Maps a global variables reference back to a backend-local reference.
     */
    public int unmapVariablesReference(int globalRef) {
        if (globalRef == 0) return 0;
        BackendType type = IdRange.backendForVariables(globalRef);
        return IdRange.variablesFor(type).toLocal(globalRef);
    }
    
    /**
     * Gets the backend type for a global variables reference.
     */
    public BackendType getBackendForVariables(int globalRef) {
        return IdRange.backendForVariables(globalRef);
    }
    
    // ========== Object Mapping Helpers ==========
    
    /**
     * Maps a list of stack frames from backend-local to global IDs.
     */
    public List<StackFrame> mapStackFrames(BackendType type, List<StackFrame> frames) {
        return frames.stream()
            .map(f -> new StackFrame(
                mapFrameId(type, f.id()),
                f.name(),
                f.sourcePath(),
                f.sourceName(),
                f.line(),
                f.column(),
                f.presentationHint()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Maps a list of scopes from backend-local to global IDs.
     */
    public List<Scope> mapScopes(BackendType type, List<Scope> scopes) {
        return scopes.stream()
            .map(s -> new Scope(
                s.name(),
                mapVariablesReference(type, s.variablesReference()),
                s.namedVariables(),
                s.indexedVariables(),
                s.expensive()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Maps a list of variables from backend-local to global IDs.
     */
    public List<Variable> mapVariables(BackendType type, List<Variable> variables) {
        return variables.stream()
            .map(v -> new Variable(
                v.name(),
                v.value(),
                v.type(),
                mapVariablesReference(type, v.variablesReference()),
                v.namedVariables(),
                v.indexedVariables(),
                v.evaluateName()
            ))
            .collect(Collectors.toList());
    }
    
    /**
     * Maps a list of breakpoints from backend-local to global IDs.
     */
    public List<Breakpoint> mapBreakpoints(BackendType type, List<Breakpoint> breakpoints) {
        // Breakpoint IDs don't need mapping - they're file-scoped
        // But we could add mapping here if needed in the future
        return breakpoints;
    }
}

