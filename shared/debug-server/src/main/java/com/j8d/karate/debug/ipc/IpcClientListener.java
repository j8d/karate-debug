package com.j8d.karate.debug.ipc;

/**
 * Listener interface for IPC events received from the child process.
 */
public interface IpcClientListener {
    
    /**
     * Called when an event is received from the child process.
     * 
     * @param event The event message
     */
    void onEvent(IpcMessage event);
    
    /**
     * Called when the connection to the child process is established.
     */
    void onConnected();
    
    /**
     * Called when the connection to the child process is closed.
     * 
     * @param reason A description of why the connection closed
     */
    void onDisconnected(String reason);
    
    /**
     * Called when an error occurs in the IPC communication.
     * 
     * @param error The error that occurred
     */
    void onError(Exception error);
}

