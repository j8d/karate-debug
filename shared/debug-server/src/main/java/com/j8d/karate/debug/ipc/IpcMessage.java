package com.j8d.karate.debug.ipc;

import com.google.gson.JsonObject;

/**
 * Base class for IPC messages between parent (coordinator) and child (runner) processes.
 * 
 * Message format (JSON over socket, similar to DAP):
 * {
 *   "seq": 1,
 *   "type": "request" | "response" | "event",
 *   "command": "...",      // for requests
 *   "request_seq": 1,      // for responses
 *   "success": true,       // for responses
 *   "event": "...",        // for events
 *   "body": { ... }        // payload
 * }
 */
public class IpcMessage {
    
    private int seq;
    private String type;
    private String command;      // For requests
    private Integer requestSeq;  // For responses
    private Boolean success;     // For responses
    private String event;        // For events
    private JsonObject body;
    
    // ========== Constructors ==========
    
    protected IpcMessage() {}
    
    // ========== Factory Methods ==========
    
    /**
     * Creates a request message.
     */
    public static IpcMessage request(int seq, String command, JsonObject body) {
        IpcMessage msg = new IpcMessage();
        msg.seq = seq;
        msg.type = "request";
        msg.command = command;
        msg.body = body;
        return msg;
    }
    
    /**
     * Creates a response message.
     */
    public static IpcMessage response(int seq, int requestSeq, boolean success, JsonObject body) {
        IpcMessage msg = new IpcMessage();
        msg.seq = seq;
        msg.type = "response";
        msg.requestSeq = requestSeq;
        msg.success = success;
        msg.body = body;
        return msg;
    }
    
    /**
     * Creates an event message.
     */
    public static IpcMessage event(int seq, String event, JsonObject body) {
        IpcMessage msg = new IpcMessage();
        msg.seq = seq;
        msg.type = "event";
        msg.event = event;
        msg.body = body;
        return msg;
    }
    
    // ========== Type Checks ==========
    
    public boolean isRequest() {
        return "request".equals(type);
    }
    
    public boolean isResponse() {
        return "response".equals(type);
    }
    
    public boolean isEvent() {
        return "event".equals(type);
    }
    
    // ========== Getters ==========
    
    public int getSeq() {
        return seq;
    }
    
    public String getType() {
        return type;
    }
    
    public String getCommand() {
        return command;
    }
    
    public Integer getRequestSeq() {
        return requestSeq;
    }
    
    public Boolean getSuccess() {
        return success;
    }
    
    public String getEvent() {
        return event;
    }
    
    public JsonObject getBody() {
        return body;
    }
    
    // ========== Convenience Methods ==========
    
    /**
     * Gets a string from the body, or null if not present.
     */
    public String getBodyString(String key) {
        if (body == null || !body.has(key)) return null;
        return body.get(key).getAsString();
    }
    
    /**
     * Gets an int from the body, or the default if not present.
     */
    public int getBodyInt(String key, int defaultValue) {
        if (body == null || !body.has(key)) return defaultValue;
        return body.get(key).getAsInt();
    }
    
    /**
     * Gets a boolean from the body, or the default if not present.
     */
    public boolean getBodyBoolean(String key, boolean defaultValue) {
        if (body == null || !body.has(key)) return defaultValue;
        return body.get(key).getAsBoolean();
    }
    
    @Override
    public String toString() {
        return "IpcMessage{type=" + type + ", seq=" + seq + 
               (command != null ? ", command=" + command : "") +
               (event != null ? ", event=" + event : "") + "}";
    }
}

