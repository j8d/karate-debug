package com.j8d.karate.debug.backend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.j8d.karate.debug.jdi.JdiClient;
import com.j8d.karate.debug.jdi.JdiEventListener;

/**
 * DebugBackend implementation for Java debugging via JDI.
 *
 * Connects to the child JVM via JDWP to debug Java code executed
 * within Karate tests (Java interop, custom step definitions, etc.).
 */
public class JavaBackend implements DebugBackend, JdiEventListener {

    private static final Logger log = LoggerFactory.getLogger(JavaBackend.class);

    private final JdiClient jdiClient;
    private final String host;
    private final int port;

    private BackendEventListener listener;
    private volatile boolean ready = false;

    // Thread ID mapping: JDI thread uniqueID -> our thread ID
    private final Map<Long, Integer> jdiToLocalThreadId = new ConcurrentHashMap<>();
    private final Map<Integer, Long> localToJdiThreadId = new ConcurrentHashMap<>();
    private final AtomicInteger nextThreadId = new AtomicInteger(1);

    // Frame ID mapping: our frame ID -> (threadId, frameIndex)
    private final Map<Integer, FrameRef> frameIdToRef = new ConcurrentHashMap<>();
    private final AtomicInteger nextFrameId = new AtomicInteger(1);

    // Variable reference mapping: our varRef -> ObjectReference
    private final Map<Integer, ObjectReference> varRefToObject = new ConcurrentHashMap<>();
    private final AtomicInteger nextVarRef = new AtomicInteger(1);

    // Breakpoint tracking
    private final Map<String, List<Integer>> fileToBreakpointIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextBreakpointId = new AtomicInteger(1);

    /**
     * Creates a JavaBackend that will connect to the given JDWP endpoint.
     */
    public JavaBackend(String host, int port) {
        this.host = host;
        this.port = port;
        this.jdiClient = new JdiClient();
        this.jdiClient.setListener(this);
    }

    // ========== DebugBackend Implementation ==========

    @Override
    public BackendType getType() {
        return BackendType.JAVA;
    }

    @Override
    public void initialize(BackendEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        log.info("Starting JavaBackend, connecting to {}:{}", host, port);

        try {
            jdiClient.connect(host, port);
            ready = true;
            log.info("JavaBackend ready");
        } catch (IOException e) {
            log.error("Failed to connect to JVM", e);
        }
    }

    @Override
    public void stop() {
        log.info("Stopping JavaBackend");
        ready = false;
        jdiClient.disconnect();
    }

    @Override
    public boolean isReady() {
        return ready && jdiClient.isConnected();
    }

    @Override
    public boolean canHandleFile(String filePath) {
        if (filePath == null) return false;
        return filePath.toLowerCase().endsWith(".java");
    }

    @Override
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> breakpoints) {
        List<Breakpoint> results = new ArrayList<>();

        // Extract class name from file path
        String className = extractClassName(filePath);
        if (className == null) {
            log.warn("Could not determine class name for: {}", filePath);
            for (BreakpointRequest req : breakpoints) {
                results.add(Breakpoint.unverified(nextBreakpointId.getAndIncrement(),
                    req.line(), filePath, "Could not determine class name"));
            }
            return results;
        }

        // Remove existing breakpoints for this file
        removeBreakpointsForFile(filePath);

        // Set new breakpoints
        List<Integer> bpIds = new ArrayList<>();
        for (BreakpointRequest req : breakpoints) {
            int bpId = nextBreakpointId.getAndIncrement();
            boolean set = jdiClient.setBreakpoint(className, req.line(), bpId);

            if (set) {
                results.add(Breakpoint.verified(bpId, req.line(), filePath));
            } else {
                // Pending - class not loaded yet
                results.add(Breakpoint.pending(bpId, req.line(), filePath));
            }
            bpIds.add(bpId);
        }

        fileToBreakpointIds.put(filePath, bpIds);
        return results;
    }

    private void removeBreakpointsForFile(String filePath) {
        List<Integer> bpIds = fileToBreakpointIds.remove(filePath);
        if (bpIds != null) {
            for (int bpId : bpIds) {
                jdiClient.removeBreakpoint(bpId);
            }
        }
    }

    // ========== Execution Control ==========

    @Override
    public void resume(int threadId) {
        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId != null) {
            ThreadReference thread = jdiClient.findThread(jdiThreadId);
            if (thread != null) {
                jdiClient.resumeThread(thread);
            }
        }
    }

    @Override
    public void stepOver(int threadId) {
        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId != null) {
            ThreadReference thread = jdiClient.findThread(jdiThreadId);
            if (thread != null) {
                jdiClient.stepOver(thread);
            }
        }
    }

    @Override
    public void stepInto(int threadId) {
        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId != null) {
            ThreadReference thread = jdiClient.findThread(jdiThreadId);
            if (thread != null) {
                jdiClient.stepInto(thread);
            }
        }
    }

    @Override
    public void stepOut(int threadId) {
        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId != null) {
            ThreadReference thread = jdiClient.findThread(jdiThreadId);
            if (thread != null) {
                jdiClient.stepOut(thread);
            }
        }
    }

    @Override
    public void pause(int threadId) {
        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId != null) {
            ThreadReference thread = jdiClient.findThread(jdiThreadId);
            if (thread != null) {
                thread.suspend();
            }
        }
    }

    // ========== Stack Frame Inspection ==========

    @Override
    public List<com.j8d.karate.debug.backend.StackFrame> getStackFrames(int threadId) {
        List<com.j8d.karate.debug.backend.StackFrame> frames = new ArrayList<>();

        Long jdiThreadId = localToJdiThreadId.get(threadId);
        if (jdiThreadId == null) return frames;

        ThreadReference thread = jdiClient.findThread(jdiThreadId);
        if (thread == null) return frames;

        try {
            List<StackFrame> jdiFrames = thread.frames();
            for (int i = 0; i < jdiFrames.size(); i++) {
                StackFrame jdiFrame = jdiFrames.get(i);
                Location loc = jdiFrame.location();

                int frameId = nextFrameId.getAndIncrement();
                frameIdToRef.put(frameId, new FrameRef(jdiThreadId, i));

                String sourcePath = extractSourcePath(loc);
                String sourceName = extractSourceName(loc);
                String methodName = loc.method().name();
                String className = loc.declaringType().name();
                String displayName = className + "." + methodName;

                frames.add(com.j8d.karate.debug.backend.StackFrame.of(
                    frameId,
                    displayName,
                    sourcePath,
                    sourceName,
                    loc.lineNumber()
                ));
            }
        } catch (IncompatibleThreadStateException e) {
            log.warn("Thread not suspended: {}", threadId);
        }

        return frames;
    }

    @Override
    public List<Scope> getScopes(int frameId) {
        List<Scope> scopes = new ArrayList<>();

        FrameRef ref = frameIdToRef.get(frameId);
        if (ref == null) return scopes;

        ThreadReference thread = jdiClient.findThread(ref.threadId());
        if (thread == null) return scopes;

        try {
            StackFrame frame = thread.frames().get(ref.frameIndex());

            // Local variables scope
            int localVarRef = nextVarRef.getAndIncrement();
            scopes.add(Scope.of("Locals", localVarRef));

            // Store frame reference for later variable lookup
            varRefToObject.put(localVarRef, null); // Special marker for locals

            // "this" object scope if available
            ObjectReference thisObj = frame.thisObject();
            if (thisObj != null) {
                int thisVarRef = nextVarRef.getAndIncrement();
                varRefToObject.put(thisVarRef, thisObj);
                scopes.add(Scope.expensive("this", thisVarRef));
            }

        } catch (IncompatibleThreadStateException | IndexOutOfBoundsException e) {
            log.warn("Failed to get scopes for frame {}", frameId);
        }

        return scopes;
    }

    @Override
    public List<Variable> getVariables(int variablesReference) {
        List<Variable> variables = new ArrayList<>();

        ObjectReference obj = varRefToObject.get(variablesReference);

        if (obj == null) {
            // This is a "locals" scope - get from current frame
            // For now, return empty - would need to track frame context
            return variables;
        }

        // Get fields from the object
        ReferenceType refType = obj.referenceType();
        for (Field field : refType.allFields()) {
            if (field.isStatic()) continue;

            Value value = obj.getValue(field);
            variables.add(valueToVariable(field.name(), value));
        }

        return variables;
    }

    @Override
    public EvaluateResult evaluate(int frameId, String expression, String context) {
        FrameRef ref = frameIdToRef.get(frameId);
        if (ref == null) {
            return EvaluateResult.error("Invalid frame");
        }

        ThreadReference thread = jdiClient.findThread(ref.threadId());
        if (thread == null) {
            return EvaluateResult.error("Thread not found");
        }

        try {
            StackFrame frame = thread.frames().get(ref.frameIndex());

            // Try to find a local variable with this name
            LocalVariable localVar = frame.visibleVariableByName(expression);
            if (localVar != null) {
                Value value = frame.getValue(localVar);
                return valueToEvaluateResult(value);
            }

            // Try to find a field on "this"
            ObjectReference thisObj = frame.thisObject();
            if (thisObj != null) {
                Field field = thisObj.referenceType().fieldByName(expression);
                if (field != null) {
                    Value value = thisObj.getValue(field);
                    return valueToEvaluateResult(value);
                }
            }

            return EvaluateResult.error("Cannot evaluate: " + expression);

        } catch (Exception e) {
            return EvaluateResult.error(e.getMessage());
        }
    }

    @Override
    public SetVariableResult setVariable(int variablesReference, String name, String value) {
        // Variable modification via JDI is complex - would need to parse the value
        // and create appropriate JDI Value objects
        log.warn("setVariable not implemented for Java backend");
        return SetVariableResult.simple(value, "unknown");
    }

    // ========== JdiEventListener Implementation ==========

    @Override
    public void onBreakpointHit(ThreadReference thread, Location location) {
        int threadId = getOrCreateLocalThreadId(thread);
        String filePath = extractSourcePath(location);
        int line = location.lineNumber();

        if (listener != null) {
            listener.onStopped(this, threadId, "breakpoint", filePath + ":" + line);
        }
    }

    @Override
    public void onStepComplete(ThreadReference thread, Location location) {
        int threadId = getOrCreateLocalThreadId(thread);
        String filePath = extractSourcePath(location);
        int line = location.lineNumber();

        if (listener != null) {
            listener.onStopped(this, threadId, "step", filePath + ":" + line);
        }
    }

    @Override
    public void onException(ThreadReference thread, Location location, String exceptionTypeName, boolean isCaught) {
        int threadId = getOrCreateLocalThreadId(thread);
        String filePath = extractSourcePath(location);
        int line = location.lineNumber();

        if (listener != null) {
            listener.onStopped(this, threadId, "exception", exceptionTypeName + " at " + filePath + ":" + line);
        }
    }

    @Override
    public void onThreadStart(ThreadReference thread) {
        // Map the thread ID when it starts
        getOrCreateLocalThreadId(thread);
    }

    @Override
    public void onThreadDeath(ThreadReference thread) {
        // Clean up thread mapping
        long jdiId = thread.uniqueID();
        Integer localId = jdiToLocalThreadId.remove(jdiId);
        if (localId != null) {
            localToJdiThreadId.remove(localId);
        }
    }

    @Override
    public void onClassPrepare(String className) {
        // Pending breakpoints are handled by JdiClient
        log.debug("Class prepared: {}", className);
    }

    @Override
    public void onVmDisconnect() {
        ready = false;
        if (listener != null) {
            listener.onTerminated(this);
        }
    }

    @Override
    public void onVmDeath() {
        ready = false;
        if (listener != null) {
            listener.onTerminated(this);
        }
    }

    // ========== Helper Methods ==========

    private int getOrCreateLocalThreadId(ThreadReference thread) {
        long jdiId = thread.uniqueID();
        return jdiToLocalThreadId.computeIfAbsent(jdiId, k -> {
            int localId = nextThreadId.getAndIncrement();
            localToJdiThreadId.put(localId, jdiId);
            return localId;
        });
    }

    private String extractClassName(String filePath) {
        if (filePath == null) return null;

        // Convert file path to class name
        // e.g., /path/to/src/main/java/com/example/MyClass.java -> com.example.MyClass
        String path = filePath.replace('\\', '/');

        // Find src/main/java or src/test/java
        int srcIdx = path.indexOf("/src/main/java/");
        if (srcIdx == -1) {
            srcIdx = path.indexOf("/src/test/java/");
        }

        if (srcIdx != -1) {
            String classPath = path.substring(srcIdx + 15); // skip "/src/main/java/"
            if (classPath.endsWith(".java")) {
                classPath = classPath.substring(0, classPath.length() - 5);
            }
            return classPath.replace('/', '.');
        }

        // Fallback: just use the file name
        int lastSlash = path.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    private String extractSourcePath(Location location) {
        try {
            return location.sourcePath();
        } catch (AbsentInformationException e) {
            return location.declaringType().name().replace('.', '/') + ".java";
        }
    }

    private String extractSourceName(Location location) {
        try {
            return location.sourceName();
        } catch (AbsentInformationException e) {
            String className = location.declaringType().name();
            int lastDot = className.lastIndexOf('.');
            return (lastDot >= 0 ? className.substring(lastDot + 1) : className) + ".java";
        }
    }

    private Variable valueToVariable(String name, Value value) {
        if (value == null) {
            return Variable.simple(name, "null", "null");
        }

        String type = value.type().name();
        String displayValue;
        int varRef = 0;

        if (value instanceof StringReference strRef) {
            displayValue = "\"" + strRef.value() + "\"";
        } else if (value instanceof PrimitiveValue primVal) {
            displayValue = primVal.toString();
        } else if (value instanceof ArrayReference arrayRef) {
            displayValue = type + "[" + arrayRef.length() + "]";
            varRef = nextVarRef.getAndIncrement();
            varRefToObject.put(varRef, arrayRef);
        } else if (value instanceof ObjectReference objRef) {
            displayValue = type + "@" + objRef.uniqueID();
            varRef = nextVarRef.getAndIncrement();
            varRefToObject.put(varRef, objRef);
        } else {
            displayValue = value.toString();
        }

        if (varRef > 0) {
            return Variable.withChildren(name, displayValue, type, varRef);
        } else {
            return Variable.simple(name, displayValue, type);
        }
    }

    private EvaluateResult valueToEvaluateResult(Value value) {
        if (value == null) {
            return EvaluateResult.simple("null", "null");
        }

        String type = value.type().name();
        String displayValue;
        int varRef = 0;

        if (value instanceof StringReference strRef) {
            displayValue = "\"" + strRef.value() + "\"";
        } else if (value instanceof PrimitiveValue primVal) {
            displayValue = primVal.toString();
        } else if (value instanceof ArrayReference arrayRef) {
            displayValue = type + "[" + arrayRef.length() + "]";
            varRef = nextVarRef.getAndIncrement();
            varRefToObject.put(varRef, arrayRef);
        } else if (value instanceof ObjectReference objRef) {
            displayValue = type + "@" + objRef.uniqueID();
            varRef = nextVarRef.getAndIncrement();
            varRefToObject.put(varRef, objRef);
        } else {
            displayValue = value.toString();
        }

        return EvaluateResult.withChildren(displayValue, type, varRef);
    }

    // ========== Inner Classes ==========

    /**
     * Reference to a stack frame: (thread ID, frame index within thread)
     */
    private record FrameRef(long threadId, int frameIndex) {}
}

