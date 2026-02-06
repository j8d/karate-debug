package com.j8d.karate.debug.jdi;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.StepRequest;

/**
 * Java Debug Interface (JDI) client for debugging Java code.
 * 
 * Connects to a JVM via JDWP and provides debugging capabilities
 * including breakpoints, stepping, and variable inspection.
 */
public class JdiClient {
    
    private static final Logger log = LoggerFactory.getLogger(JdiClient.class);
    
    private VirtualMachine vm;
    private EventRequestManager erm;
    private JdiEventListener listener;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread eventThread;
    
    // Breakpoint tracking: our ID -> JDI BreakpointRequest
    private final Map<Integer, BreakpointRequest> breakpointRequests = new ConcurrentHashMap<>();
    
    // Pending breakpoints for classes not yet loaded: className -> list of (line, breakpointId)
    private final Map<String, List<PendingBreakpoint>> pendingBreakpoints = new ConcurrentHashMap<>();
    
    // Step request tracking: threadId -> active StepRequest
    private final Map<Long, StepRequest> activeStepRequests = new ConcurrentHashMap<>();

    // Track original step type per thread (INTO vs OVER/OUT) for framework filtering
    // Step-over/out should ALWAYS skip framework code; step-into uses the skip settings
    private final Map<Long, Integer> originalStepDepth = new ConcurrentHashMap<>();

    // Method entry request for cross-language step-into
    private MethodEntryRequest methodEntryRequest;

    // Step filtering configuration
    private final boolean skipJdkClasses;
    private final boolean skipKarateFramework;
    private final boolean skipKarateDependencies;

    /**
     * Creates a JdiClient with default settings (skip all framework classes).
     */
    public JdiClient() {
        this(true, true, true);
    }

    /**
     * Creates a JdiClient with configurable step filtering.
     *
     * @param skipJdkClasses Whether to auto-skip JDK core classes when stepping
     * @param skipKarateFramework Whether to auto-skip Karate framework classes when stepping
     * @param skipKarateDependencies Whether to auto-skip Karate's dependencies (jsonpath, netty, etc.)
     */
    public JdiClient(boolean skipJdkClasses, boolean skipKarateFramework, boolean skipKarateDependencies) {
        this.skipJdkClasses = skipJdkClasses;
        this.skipKarateFramework = skipKarateFramework;
        this.skipKarateDependencies = skipKarateDependencies;
        log.trace("JdiClient created with skipJdkClasses={}, skipKarateFramework={}, skipKarateDependencies={}",
                skipJdkClasses, skipKarateFramework, skipKarateDependencies);
    }

    /**
     * Sets the event listener for JDI events.
     */
    public void setListener(JdiEventListener listener) {
        this.listener = listener;
    }
    
    /**
     * Connects to a JVM via JDWP socket.
     * 
     * @param host The host to connect to (usually "localhost")
     * @param port The JDWP port
     * @throws IOException If connection fails
     */
    public void connect(String host, int port) throws IOException {
        log.debug("Connecting to JVM at {}:{}", host, port);

        try {
            AttachingConnector connector = findSocketAttachConnector();
            if (connector == null) {
                throw new IOException("Socket attaching connector not found");
            }

            Map<String, Connector.Argument> args = connector.defaultArguments();
            args.get("hostname").setValue(host);
            args.get("port").setValue(String.valueOf(port));

            vm = connector.attach(args);
            erm = vm.eventRequestManager();

            // Enable class prepare events for deferred breakpoints
            ClassPrepareRequest cpr = erm.createClassPrepareRequest();
            cpr.enable();

            log.debug("Connected to JVM: {}", vm.name());
            
            // Start event loop
            startEventLoop();
            
        } catch (IllegalConnectorArgumentsException e) {
            throw new IOException("Invalid connector arguments", e);
        }
    }
    
    /**
     * Disconnects from the JVM.
     */
    public void disconnect() {
        log.trace("Disconnecting from JVM");
        running.set(false);
        
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception e) {
                log.warn("Error disposing VM", e);
            }
            vm = null;
        }
        
        if (eventThread != null) {
            eventThread.interrupt();
            eventThread = null;
        }
    }
    
    /**
     * Returns true if connected to the JVM.
     */
    public boolean isConnected() {
        return vm != null && running.get();
    }
    
    /**
     * Returns the connected VirtualMachine.
     */
    public VirtualMachine getVm() {
        return vm;
    }
    
    /**
     * Returns the EventRequestManager.
     */
    public EventRequestManager getEventRequestManager() {
        return erm;
    }

    // ========== Breakpoint Management ==========

    /**
     * Sets a breakpoint at the specified location.
     *
     * @param className Fully qualified class name
     * @param lineNumber 1-based line number
     * @param breakpointId Our internal breakpoint ID
     * @return true if breakpoint was set, false if class not loaded (pending)
     */
    public boolean setBreakpoint(String className, int lineNumber, int breakpointId) {
        List<ReferenceType> classes = vm.classesByName(className);

        if (classes.isEmpty()) {
            // Class not loaded yet - add to pending
            log.debug("Class {} not loaded, deferring breakpoint at line {}", className, lineNumber);
            pendingBreakpoints.computeIfAbsent(className, k -> new java.util.ArrayList<>())
                .add(new PendingBreakpoint(lineNumber, breakpointId));
            return false;
        }

        ReferenceType refType = classes.get(0);
        return setBreakpointOnClass(refType, lineNumber, breakpointId);
    }

    private boolean setBreakpointOnClass(ReferenceType refType, int lineNumber, int breakpointId) {
        try {
            List<Location> locations = refType.locationsOfLine(lineNumber);
            if (locations.isEmpty()) {
                log.warn("No code at line {} in {}", lineNumber, refType.name());
                return false;
            }

            Location location = locations.get(0);
            BreakpointRequest bpReq = erm.createBreakpointRequest(location);

            // IMPORTANT: Only suspend the event thread, not all threads.
            // This allows the IPC-Sender thread to continue running during debugging.
            bpReq.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD);

            bpReq.enable();

            breakpointRequests.put(breakpointId, bpReq);
            log.debug("Set breakpoint {} at {}:{}", breakpointId, refType.name(), lineNumber);
            return true;

        } catch (AbsentInformationException e) {
            log.warn("No line info for {} (compile with -g)", refType.name());
            return false;
        }
    }

    /**
     * Removes a breakpoint by ID.
     */
    public void removeBreakpoint(int breakpointId) {
        BreakpointRequest bpReq = breakpointRequests.remove(breakpointId);
        if (bpReq != null) {
            erm.deleteEventRequest(bpReq);
            log.debug("Removed breakpoint {}", breakpointId);
        }
    }

    /**
     * Removes all breakpoints.
     */
    public void removeAllBreakpoints() {
        for (BreakpointRequest bpReq : breakpointRequests.values()) {
            erm.deleteEventRequest(bpReq);
        }
        breakpointRequests.clear();
        pendingBreakpoints.clear();
    }

    // ========== Execution Control ==========

    /**
     * Resumes all threads in the VM.
     */
    public void resume() {
        if (vm != null) {
            vm.resume();
        }
    }

    /**
     * Resumes a specific thread.
     */
    public void resumeThread(ThreadReference thread) {
        thread.resume();
    }

    /**
     * Creates a step request for the given thread.
     * Adds class exclusion filters to skip JDK and framework classes.
     */
    public void step(ThreadReference thread, int depth) {
        String depthName = depth == StepRequest.STEP_INTO ? "INTO" :
                          depth == StepRequest.STEP_OVER ? "OVER" : "OUT";
        log.trace("Creating step request: thread={}, depth={}, suspended={}",
                 thread.name(), depthName, thread.isSuspended());

        // Remove any existing step request for this thread
        cancelStep(thread);

        // Track original step type - only set if not already tracking (for auto-continue chains)
        // This ensures we remember the user's original intent (INTO vs OVER/OUT)
        originalStepDepth.putIfAbsent(thread.uniqueID(), depth);

        StepRequest stepReq = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        stepReq.addCountFilter(1); // Only one step

        // IMPORTANT: Only suspend the event thread, not all threads.
        // This allows the IPC-Sender thread to continue running during debugging.
        stepReq.setSuspendPolicy(StepRequest.SUSPEND_EVENT_THREAD);

        // No step filters - let users step into any code they want.
        // If they don't want to step into JDK/framework code, they can use Step Over.

        stepReq.enable();

        activeStepRequests.put(thread.uniqueID(), stepReq);

        log.trace("Step request enabled, resuming thread {}", thread.name());
        // Resume to execute the step
        thread.resume();
        log.trace("Thread {} resumed", thread.name());
    }

    /**
     * Steps over the current line.
     */
    public void stepOver(ThreadReference thread) {
        step(thread, StepRequest.STEP_OVER);
    }

    /**
     * Steps into the current line.
     */
    public void stepInto(ThreadReference thread) {
        step(thread, StepRequest.STEP_INTO);
    }

    /**
     * Steps out of the current method.
     */
    public void stepOut(ThreadReference thread) {
        step(thread, StepRequest.STEP_OUT);
    }

    /**
     * Cancels any pending step request for the thread.
     */
    public void cancelStep(ThreadReference thread) {
        StepRequest stepReq = activeStepRequests.remove(thread.uniqueID());
        if (stepReq != null) {
            erm.deleteEventRequest(stepReq);
        }
    }

    /**
     * Cancels all active step requests.
     * Called when a Java step exits to framework code and we need to clean up.
     */
    public void cancelAllSteps() {
        if (erm == null) return;
        for (StepRequest stepReq : activeStepRequests.values()) {
            try {
                erm.deleteEventRequest(stepReq);
            } catch (Exception e) {
                log.debug("Error canceling step request: {}", e.getMessage());
            }
        }
        activeStepRequests.clear();
        log.debug("Cancelled all active step requests");
    }

    /**
     * Enables method entry events for cross-language step-into.
     * Filters to only user code (excludes JDK, Karate, GraalVM internals).
     */
    public void enableMethodEntry() {
        if (methodEntryRequest != null) {
            log.debug("Method entry already enabled");
            return;
        }
        if (erm == null) {
            log.warn("Cannot enable method entry: not connected");
            return;
        }

        methodEntryRequest = erm.createMethodEntryRequest();

        // IMPORTANT: Only suspend the event thread, not all threads.
        // This allows the IPC-Sender thread to continue running during debugging.
        methodEntryRequest.setSuspendPolicy(MethodEntryRequest.SUSPEND_EVENT_THREAD);

        // Filter out JDK classes - always excluded (no source code)
        methodEntryRequest.addClassExclusionFilter("java.*");
        methodEntryRequest.addClassExclusionFilter("javax.*");
        methodEntryRequest.addClassExclusionFilter("sun.*");
        methodEntryRequest.addClassExclusionFilter("com.sun.*");
        methodEntryRequest.addClassExclusionFilter("jdk.*");

        // Conditionally exclude Karate framework classes based on skip setting
        // When skipKarateFramework is false, users can step into Karate source code
        if (skipKarateFramework) {
            methodEntryRequest.addClassExclusionFilter("com.intuit.karate.*");
        }

        // Always exclude GraalVM/Truffle runtime - internal execution classes
        methodEntryRequest.addClassExclusionFilter("org.graalvm.*");
        methodEntryRequest.addClassExclusionFilter("com.oracle.truffle.*");
        methodEntryRequest.addClassExclusionFilter("com.oracle.js.*");
        methodEntryRequest.addClassExclusionFilter("org.graaljs.*");
        // Our own debug infrastructure
        methodEntryRequest.addClassExclusionFilter("com.j8d.karate.debug.*");
        // Logging frameworks
        methodEntryRequest.addClassExclusionFilter("ch.qos.logback.*");
        methodEntryRequest.addClassExclusionFilter("org.slf4j.*");
        methodEntryRequest.addClassExclusionFilter("org.apache.logging.*");
        methodEntryRequest.addClassExclusionFilter("org.apache.log4j.*");
        // Common frameworks
        methodEntryRequest.addClassExclusionFilter("org.apache.commons.*");
        methodEntryRequest.addClassExclusionFilter("com.google.*");
        methodEntryRequest.addClassExclusionFilter("org.json.*");
        methodEntryRequest.addClassExclusionFilter("com.fasterxml.*");
        methodEntryRequest.addClassExclusionFilter("io.netty.*");
        methodEntryRequest.addClassExclusionFilter("org.yaml.*");

        // Conditionally exclude Karate dependencies based on skip setting
        if (skipKarateDependencies) {
            methodEntryRequest.addClassExclusionFilter("com.jayway.jsonpath.*");
            methodEntryRequest.addClassExclusionFilter("net.minidev.*");
            methodEntryRequest.addClassExclusionFilter("org.apache.http.*");
            methodEntryRequest.addClassExclusionFilter("org.thymeleaf.*");
            methodEntryRequest.addClassExclusionFilter("com.linecorp.armeria.*");
            methodEntryRequest.addClassExclusionFilter("de.siegmar.fastcsv.*");
            methodEntryRequest.addClassExclusionFilter("org.antlr.*");
            methodEntryRequest.addClassExclusionFilter("com.github.javaparser.*");
        }

        methodEntryRequest.enable();
        log.trace("Method entry events enabled for cross-language step-into");
    }

    /**
     * Disables method entry events.
     */
    public void disableMethodEntry() {
        if (methodEntryRequest != null) {
            methodEntryRequest.disable();
            erm.deleteEventRequest(methodEntryRequest);
            methodEntryRequest = null;
            log.trace("Method entry events disabled");
        }
    }

    /**
     * Suspends all threads.
     */
    public void suspend() {
        if (vm != null) {
            vm.suspend();
        }
    }

    // ========== Event Loop ==========

    private void startEventLoop() {
        running.set(true);
        eventThread = new Thread(this::eventLoop, "JDI-EventLoop");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private void eventLoop() {
        EventQueue queue = vm.eventQueue();

        while (running.get()) {
            try {
                EventSet events = queue.remove(1000); // 1 second timeout
                if (events == null) continue;

                boolean shouldResume = true;

                for (Event event : events) {
                    shouldResume = handleEvent(event) && shouldResume;
                }

                if (shouldResume) {
                    events.resume();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (com.sun.jdi.VMDisconnectedException e) {
                log.info("VM disconnected");
                running.set(false);
                if (listener != null) {
                    listener.onVmDisconnect();
                }
                break;
            } catch (Exception e) {
                log.error("Error in event loop", e);
            }
        }
    }

    /**
     * Handles a JDI event.
     * @return true if the event set should be resumed, false to keep suspended
     */
    private boolean handleEvent(Event event) {
        if (listener == null) return true;

        if (event instanceof BreakpointEvent bpEvent) {
            listener.onBreakpointHit(bpEvent.thread(), bpEvent.location());
            return false; // Stay suspended at breakpoint

        } else if (event instanceof StepEvent stepEvent) {
            Location loc = stepEvent.location();
            String className = loc.declaringType().name();
            long threadId = stepEvent.thread().uniqueID();

            // Get the original step type (what the user requested: INTO vs OVER/OUT)
            Integer origDepth = originalStepDepth.get(threadId);

            log.trace("StepEvent received: thread={}, class={}, method={}, line={}, origStepType={}",
                     stepEvent.thread().name(),
                     className,
                     loc.method().name(),
                     loc.lineNumber(),
                     origDepth == null ? "unknown" :
                         (origDepth == StepRequest.STEP_INTO ? "INTO" :
                          origDepth == StepRequest.STEP_OVER ? "OVER" : "OUT"));

            // Check if we should skip this framework code
            // - Step Over/Out: ALWAYS skip framework code (user wants to stay at their level)
            // - Step Into: Use the skip settings (user may want to descend into framework)
            if (shouldSkipFrameworkClass(className, origDepth)) {
                log.debug("StepEvent in framework code, auto-continuing step");
                // Issue another step to skip framework code
                // Use STEP_OUT to quickly exit framework code back to user code
                cancelStep(stepEvent.thread());
                step(stepEvent.thread(), StepRequest.STEP_OUT);
                return false; // Stay suspended, the new step request will resume
            }

            // User code - clean up and report
            cancelStep(stepEvent.thread());
            // Clear the original step tracking since we're done with this step sequence
            originalStepDepth.remove(threadId);
            listener.onStepComplete(stepEvent.thread(), stepEvent.location());
            return false; // Stay suspended after step

        } else if (event instanceof ExceptionEvent exEvent) {
            String exceptionType = exEvent.exception().referenceType().name();
            boolean isCaught = exEvent.catchLocation() != null;
            listener.onException(exEvent.thread(), exEvent.location(), exceptionType, isCaught);
            return false; // Stay suspended on exception

        } else if (event instanceof MethodEntryEvent meEvent) {
            // Method entry for cross-language step-into
            listener.onMethodEntry(meEvent.thread(), meEvent.location());
            return false; // Stay suspended at method entry

        } else if (event instanceof ClassPrepareEvent cpEvent) {
            String className = cpEvent.referenceType().name();
            handleClassPrepare(cpEvent.referenceType());
            listener.onClassPrepare(className);
            return true; // Resume after class prepare

        } else if (event instanceof ThreadStartEvent tsEvent) {
            listener.onThreadStart(tsEvent.thread());
            return true;

        } else if (event instanceof ThreadDeathEvent tdEvent) {
            listener.onThreadDeath(tdEvent.thread());
            return true;

        } else if (event instanceof VMDeathEvent) {
            listener.onVmDeath();
            running.set(false);
            return true;

        } else if (event instanceof VMDisconnectEvent) {
            listener.onVmDisconnect();
            running.set(false);
            return true;
        }

        return true;
    }

    /**
     * Handles class prepare event - sets any pending breakpoints.
     */
    private void handleClassPrepare(ReferenceType refType) {
        String className = refType.name();
        List<PendingBreakpoint> pending = pendingBreakpoints.remove(className);

        if (pending != null) {
            for (PendingBreakpoint pb : pending) {
                setBreakpointOnClass(refType, pb.lineNumber(), pb.breakpointId());
            }
        }
    }

    // ========== Helper Methods ==========

    private AttachingConnector findSocketAttachConnector() {
        for (Connector connector : Bootstrap.virtualMachineManager().allConnectors()) {
            if (connector instanceof AttachingConnector &&
                connector.name().equals("com.sun.jdi.SocketAttach")) {
                return (AttachingConnector) connector;
            }
        }
        return null;
    }

    /**
     * Finds a thread by its unique ID.
     */
    public ThreadReference findThread(long threadId) {
        for (ThreadReference thread : vm.allThreads()) {
            if (thread.uniqueID() == threadId) {
                return thread;
            }
        }
        return null;
    }

    // ========== Framework Code Detection ==========

    /**
     * JDK core class prefixes to optionally skip when stepping.
     */
    private static final String[] JDK_PREFIXES = {
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
    };

    /**
     * GraalVM/Truffle runtime prefixes - always skipped.
     */
    private static final String[] GRAALVM_PREFIXES = {
        "com.oracle.truffle.",
        "org.graalvm.",
    };

    /**
     * Karate framework prefix - optionally skipped.
     */
    private static final String KARATE_PREFIX = "com.intuit.karate.";

    /**
     * Karate's internal dependencies - skipped when skipKarateFramework=true.
     * When skipKarateFramework=false, user can step through all of these.
     */
    private static final String[] KARATE_DEPENDENCY_PREFIXES = {
        "com.jayway.jsonpath.",    // JSON path parsing
        "net.minidev.",            // JSON Smart (used by jsonpath)
        "org.slf4j.",              // Logging
        "ch.qos.logback.",         // Logging implementation
        "io.netty.",               // HTTP client
        "org.apache.http.",        // HTTP client (Apache)
        "org.thymeleaf.",          // Template engine
        "com.linecorp.armeria.",   // HTTP framework
        "de.siegmar.fastcsv.",     // CSV parsing
        "org.antlr.",              // Parser generator
        "org.yaml.snakeyaml.",     // YAML parsing
        "com.github.javaparser.",  // Java parsing
    };

    /**
     * Checks if a class is a JVM-generated class that has no source code.
     * These are dynamically generated by the JVM at runtime and should always be skipped.
     *
     * Examples:
     * - java.lang.invoke.LambdaForm$MH/0x0000007001434800
     * - java.lang.invoke.LambdaForm$DMH/0x0000007001425400
     * - java.lang.invoke.Invokers$Holder
     * - java.lang.invoke.DirectMethodHandle$Holder
     * - java.lang.invoke.DelegatingMethodHandle$Holder
     * - java.lang.invoke.LambdaForm$Holder
     */
    private boolean isJvmGeneratedClass(String className) {
        // LambdaForm generated classes have patterns like:
        // java.lang.invoke.LambdaForm$MH/0x... (method handle)
        // java.lang.invoke.LambdaForm$DMH/0x... (direct method handle)
        // java.lang.invoke.LambdaForm$BMH/0x... (bound method handle)
        if (className.startsWith("java.lang.invoke.LambdaForm$") && className.contains("/")) {
            return true;
        }

        // Holder classes are generated at runtime to hold method handles
        // java.lang.invoke.Invokers$Holder
        // java.lang.invoke.DirectMethodHandle$Holder
        // java.lang.invoke.DelegatingMethodHandle$Holder
        // java.lang.invoke.LambdaForm$Holder
        if (className.startsWith("java.lang.invoke.") && className.endsWith("$Holder")) {
            return true;
        }

        // Lambda proxy classes generated by LambdaMetafactory
        // e.g., com.example.MyClass$$Lambda$123/0x0000007001234567
        if (className.contains("$$Lambda$") && className.contains("/")) {
            return true;
        }

        return false;
    }

    /**
     * Checks if a class should be skipped when stepping, based on the original step type.
     *
     * Step semantics:
     * - Step Over/Out: ALWAYS skip all framework code (user wants to stay at their abstraction level)
     * - Step Into: Use the skip settings (user may intentionally want to descend into framework code)
     *
     * @param className The fully qualified class name
     * @param originalStepType The original step type (STEP_INTO, STEP_OVER, or STEP_OUT), or null if unknown
     * @return true if this class should be skipped during stepping
     */
    private boolean shouldSkipFrameworkClass(String className, Integer originalStepType) {
        // Always skip JVM-generated classes - they have no source code
        if (isJvmGeneratedClass(className)) {
            return true;
        }

        // Always skip GraalVM/Truffle runtime - these are internal execution classes
        for (String prefix : GRAALVM_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        // For Step Over/Out (or unknown), ALWAYS skip all framework code
        // The user didn't ask to descend - they want to stay at their level
        boolean isStepInto = (originalStepType != null && originalStepType == StepRequest.STEP_INTO);

        if (!isStepInto) {
            // Step Over/Out: skip ALL framework code unconditionally
            for (String prefix : JDK_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            if (className.startsWith(KARATE_PREFIX)) {
                return true;
            }
            for (String prefix : KARATE_DEPENDENCY_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        } else {
            // Step Into: use the skip settings (user may want to explore framework code)
            if (skipJdkClasses) {
                for (String prefix : JDK_PREFIXES) {
                    if (className.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            if (skipKarateFramework) {
                if (className.startsWith(KARATE_PREFIX)) {
                    return true;
                }
            }
            if (skipKarateDependencies) {
                for (String prefix : KARATE_DEPENDENCY_PREFIXES) {
                    if (className.startsWith(prefix)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ========== Inner Classes ==========

    /**
     * A breakpoint waiting for its class to be loaded.
     */
    private record PendingBreakpoint(int lineNumber, int breakpointId) {}
}

