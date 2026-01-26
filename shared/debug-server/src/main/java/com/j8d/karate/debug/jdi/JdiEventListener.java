package com.j8d.karate.debug.jdi;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;

/**
 * Listener interface for Java Debug Interface (JDI) events.
 * 
 * JDI events are notifications from the debugged JVM about state changes
 * like breakpoint hits, step completions, and thread lifecycle events.
 */
public interface JdiEventListener {
    
    /**
     * Called when a breakpoint is hit.
     * 
     * @param thread The thread that hit the breakpoint
     * @param location The location where the breakpoint was hit
     */
    void onBreakpointHit(ThreadReference thread, Location location);
    
    /**
     * Called when a step operation completes.
     * 
     * @param thread The thread that completed the step
     * @param location The new location after stepping
     */
    void onStepComplete(ThreadReference thread, Location location);
    
    /**
     * Called when an exception is thrown (if exception breakpoints are enabled).
     * 
     * @param thread The thread that threw the exception
     * @param location The location where the exception was thrown
     * @param exceptionTypeName The fully qualified name of the exception type
     * @param isCaught True if the exception will be caught
     */
    void onException(ThreadReference thread, Location location, String exceptionTypeName, boolean isCaught);
    
    /**
     * Called when a new thread starts in the debugged VM.
     * 
     * @param thread The thread that started
     */
    void onThreadStart(ThreadReference thread);
    
    /**
     * Called when a thread dies in the debugged VM.
     * 
     * @param thread The thread that died
     */
    void onThreadDeath(ThreadReference thread);
    
    /**
     * Called when a class is prepared (loaded and ready for use).
     * This is useful for setting deferred breakpoints.
     * 
     * @param className The fully qualified class name
     */
    void onClassPrepare(String className);
    
    /**
     * Called when the debugged VM disconnects.
     */
    void onVmDisconnect();
    
    /**
     * Called when the debugged VM dies (process terminates).
     */
    void onVmDeath();
}

