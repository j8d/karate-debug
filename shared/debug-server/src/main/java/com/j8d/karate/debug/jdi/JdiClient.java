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
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
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
        log.info("Connecting to JVM at {}:{}", host, port);
        
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
            
            log.info("Connected to JVM: {}", vm.name());
            
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
        log.info("Disconnecting from JVM");
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
     */
    public void step(ThreadReference thread, int depth) {
        // Remove any existing step request for this thread
        cancelStep(thread);

        StepRequest stepReq = erm.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        stepReq.addCountFilter(1); // Only one step
        stepReq.enable();

        activeStepRequests.put(thread.uniqueID(), stepReq);

        // Resume to execute the step
        thread.resume();
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
            // Clean up the step request
            cancelStep(stepEvent.thread());
            listener.onStepComplete(stepEvent.thread(), stepEvent.location());
            return false; // Stay suspended after step

        } else if (event instanceof ExceptionEvent exEvent) {
            String exceptionType = exEvent.exception().referenceType().name();
            boolean isCaught = exEvent.catchLocation() != null;
            listener.onException(exEvent.thread(), exEvent.location(), exceptionType, isCaught);
            return false; // Stay suspended on exception

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

    // ========== Inner Classes ==========

    /**
     * A breakpoint waiting for its class to be loaded.
     */
    private record PendingBreakpoint(int lineNumber, int breakpointId) {}
}

