package com.j8d.karate.debug.cdp;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Chrome DevTools Protocol client using WebSocket.
 * 
 * Provides async request/response communication with CDP-compatible debuggers
 * like GraalVM's Chrome Inspector.
 * 
 * Message format:
 * - Request: { "id": 1, "method": "Debugger.enable", "params": {} }
 * - Response: { "id": 1, "result": {} } or { "id": 1, "error": { "code": -32000, "message": "..." } }
 * - Event: { "method": "Debugger.paused", "params": { ... } }
 */
public class CdpClient {
    
    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);
    private static final Gson gson = new Gson();
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    
    private WebSocketClient webSocket;
    private CdpEventListener listener;
    private volatile boolean connected = false;
    
    /**
     * Sets the event listener for CDP events.
     */
    public void setListener(CdpEventListener listener) {
        this.listener = listener;
    }
    
    /**
     * Connects to a CDP endpoint.
     * 
     * @param webSocketUrl The WebSocket URL (e.g., ws://127.0.0.1:9229/...)
     * @return CompletableFuture that completes when connected
     */
    public CompletableFuture<Void> connect(String webSocketUrl) {
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();
        
        try {
            URI uri = new URI(webSocketUrl);
            webSocket = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("CDP connected to {}", webSocketUrl);
                    connected = true;
                    connectFuture.complete(null);
                }
                
                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.info("CDP disconnected: code={}, reason={}, remote={}", code, reason, remote);
                    connected = false;
                    if (listener != null) {
                        listener.onDisconnected(code, reason, remote);
                    }
                    // Complete any pending requests with error
                    failPendingRequests("Connection closed");
                }
                
                @Override
                public void onError(Exception ex) {
                    log.error("CDP error", ex);
                    if (listener != null) {
                        listener.onError(ex);
                    }
                    if (!connectFuture.isDone()) {
                        connectFuture.completeExceptionally(ex);
                    }
                }
            };
            
            webSocket.connect();
        } catch (Exception e) {
            connectFuture.completeExceptionally(e);
        }
        
        return connectFuture;
    }
    
    /**
     * Disconnects from the CDP endpoint.
     */
    public void disconnect() {
        if (webSocket != null) {
            webSocket.close();
            webSocket = null;
        }
        connected = false;
    }
    
    /**
     * Returns true if connected to the CDP endpoint.
     */
    public boolean isConnected() {
        return connected && webSocket != null && webSocket.isOpen();
    }
    
    /**
     * Sends a CDP command and returns a future for the response.
     * 
     * @param method The CDP method (e.g., "Debugger.enable")
     * @param params The method parameters (can be null)
     * @return CompletableFuture that completes with the result
     */
    public CompletableFuture<JsonObject> send(String method, JsonObject params) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }
        
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        
        String json = gson.toJson(request);
        log.debug("CDP send: {}", json);
        webSocket.send(json);
        
        return future;
    }
    
    /**
     * Sends a CDP command and waits for the response synchronously.
     */
    public JsonObject sendSync(String method, JsonObject params) throws Exception {
        return sendSync(method, params, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * Sends a CDP command and waits for the response with timeout.
     */
    public JsonObject sendSync(String method, JsonObject params, long timeoutMs) throws Exception {
        return send(method, params).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Handles incoming WebSocket messages.
     */
    private void handleMessage(String message) {
        log.debug("CDP recv: {}", message);

        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);

            // Check if this is a response (has "id" field)
            if (json.has("id")) {
                handleResponse(json);
            } else if (json.has("method")) {
                // This is an event
                handleEvent(json);
            }
        } catch (Exception e) {
            log.error("Failed to parse CDP message: {}", message, e);
        }
    }

    /**
     * Handles a response to a previous request.
     */
    private void handleResponse(JsonObject json) {
        int id = json.get("id").getAsInt();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);

        if (future == null) {
            log.warn("Received response for unknown request id: {}", id);
            return;
        }

        if (json.has("error")) {
            JsonObject error = json.getAsJsonObject("error");
            String errorMsg = error.has("message") ? error.get("message").getAsString() : "Unknown error";
            future.completeExceptionally(new CdpException(errorMsg, error));
        } else {
            JsonObject result = json.has("result") ? json.getAsJsonObject("result") : new JsonObject();
            future.complete(result);
        }
    }

    /**
     * Handles an asynchronous CDP event.
     */
    private void handleEvent(JsonObject json) {
        if (listener == null) {
            return;
        }

        String method = json.get("method").getAsString();
        JsonObject params = json.has("params") ? json.getAsJsonObject("params") : new JsonObject();

        switch (method) {
            case "Debugger.scriptParsed" -> handleScriptParsed(params);
            case "Debugger.paused" -> handlePaused(params);
            case "Debugger.resumed" -> listener.onResumed();
            case "Debugger.breakpointResolved" -> handleBreakpointResolved(params);
            default -> log.debug("Unhandled CDP event: {}", method);
        }
    }

    private void handleScriptParsed(JsonObject params) {
        String scriptId = params.get("scriptId").getAsString();
        String url = params.has("url") ? params.get("url").getAsString() : "";
        int startLine = params.has("startLine") ? params.get("startLine").getAsInt() : 0;
        int startColumn = params.has("startColumn") ? params.get("startColumn").getAsInt() : 0;
        int endLine = params.has("endLine") ? params.get("endLine").getAsInt() : 0;
        int endColumn = params.has("endColumn") ? params.get("endColumn").getAsInt() : 0;
        String hash = params.has("hash") ? params.get("hash").getAsString() : "";

        listener.onScriptParsed(scriptId, url, startLine, startColumn, endLine, endColumn, hash);
    }

    private void handlePaused(JsonObject params) {
        JsonArray callFramesArray = params.getAsJsonArray("callFrames");
        JsonObject[] callFrames = new JsonObject[callFramesArray.size()];
        for (int i = 0; i < callFramesArray.size(); i++) {
            callFrames[i] = callFramesArray.get(i).getAsJsonObject();
        }

        String reason = params.get("reason").getAsString();

        String[] hitBreakpoints = new String[0];
        if (params.has("hitBreakpoints")) {
            JsonArray bpArray = params.getAsJsonArray("hitBreakpoints");
            hitBreakpoints = new String[bpArray.size()];
            for (int i = 0; i < bpArray.size(); i++) {
                hitBreakpoints[i] = bpArray.get(i).getAsString();
            }
        }

        JsonObject data = params.has("data") ? params.getAsJsonObject("data") : null;

        listener.onPaused(callFrames, reason, hitBreakpoints, data);
    }

    private void handleBreakpointResolved(JsonObject params) {
        String breakpointId = params.get("breakpointId").getAsString();
        JsonObject location = params.getAsJsonObject("location");
        listener.onBreakpointResolved(breakpointId, location);
    }

    /**
     * Fails all pending requests with an error.
     */
    private void failPendingRequests(String reason) {
        Exception error = new IllegalStateException(reason);
        for (CompletableFuture<JsonObject> future : pendingRequests.values()) {
            future.completeExceptionally(error);
        }
        pendingRequests.clear();
    }

    /**
     * Exception for CDP errors.
     */
    public static class CdpException extends Exception {
        private final JsonObject errorDetails;

        public CdpException(String message, JsonObject errorDetails) {
            super(message);
            this.errorDetails = errorDetails;
        }

        public JsonObject getErrorDetails() {
            return errorDetails;
        }
    }
}

