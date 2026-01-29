package com.j8d.karate.debug.ipc;

import com.google.gson.JsonObject;

/**
 * Handler interface for IPC commands received from the parent process.
 * 
 * Implementations should handle commands and return responses synchronously.
 * Long-running operations should be handled carefully to avoid blocking.
 */
public interface IpcServerHandler {
    
    /**
     * Handles a command from the parent process.
     * 
     * @param command The command name (from IpcCommands)
     * @param body The command body (may be null)
     * @return The response body (may be null for simple acknowledgments)
     * @throws Exception if the command fails
     */
    JsonObject handleCommand(String command, JsonObject body) throws Exception;
}

