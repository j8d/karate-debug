package com.j8d.karate.debug.dap;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Debug Adapter Protocol (DAP) client using TCP socket.
 * 
 * Connects to GraalVM's built-in DAP server for JavaScript debugging.
 * 
 * DAP Message format:
 * Content-Length: <length>\r\n
 * \r\n
 * <JSON body>
 * 
 * Request: { "seq": 1, "type": "request", "command": "initialize", "arguments": {...} }
 * Response: { "seq": 1, "type": "response", "request_seq": 1, "command": "initialize", "success": true, "body": {...} }
 * Event: { "seq": 2, "type": "event", "event": "stopped", "body": {...} }
 */
public class DapClient {
    
    private static final Logger log = LoggerFactory.getLogger(DapClient.class);
    private static final Gson gson = new Gson();
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final String CONTENT_LENGTH = "Content-Length: ";
    
    private final AtomicInteger nextSeq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    
    private Socket socket;
    private OutputStream outputStream;
    private Thread readerThread;
    private DapEventListener listener;
    private volatile boolean connected = false;
    
    /**
     * Sets the event listener for DAP events.
     */
    public void setListener(DapEventListener listener) {
        this.listener = listener;
    }
    
    /**
     * Connects to a DAP server.
     * 
     * @param host The host (e.g., "127.0.0.1")
     * @param port The port
     * @return CompletableFuture that completes when connected
     */
    public CompletableFuture<Void> connect(String host, int port) {
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();
        
        try {
            socket = new Socket(host, port);
            outputStream = socket.getOutputStream();
            connected = true;
            
            // Start reader thread
            startReaderThread(socket.getInputStream());
            
            log.trace("DAP connected to {}:{}", host, port);
            connectFuture.complete(null);
        } catch (Exception e) {
            log.error("Failed to connect to DAP server at {}:{}", host, port, e);
            connectFuture.completeExceptionally(e);
        }
        
        return connectFuture;
    }
    
    /**
     * Disconnects from the DAP server.
     */
    public void disconnect() {
        connected = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            log.debug("Error closing socket", e);
        }
        socket = null;
        outputStream = null;
    }
    
    /**
     * Returns true if connected to the DAP server.
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    /**
     * Sends a DAP request and returns a future for the response.
     */
    public CompletableFuture<JsonObject> send(String command, JsonObject arguments) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected"));
        }
        
        int seq = nextSeq.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(seq, future);
        
        JsonObject request = new JsonObject();
        request.addProperty("seq", seq);
        request.addProperty("type", "request");
        request.addProperty("command", command);
        if (arguments != null) {
            request.add("arguments", arguments);
        }
        
        try {
            sendMessage(request);
        } catch (IOException e) {
            pendingRequests.remove(seq);
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Sends a DAP request and waits for the response synchronously.
     */
    public JsonObject sendSync(String command, JsonObject arguments) throws Exception {
        return sendSync(command, arguments, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * Sends a DAP request and waits for the response with timeout.
     */
    public JsonObject sendSync(String command, JsonObject arguments, long timeoutMs) throws Exception {
        return send(command, arguments).get(timeoutMs, TimeUnit.MILLISECONDS);
    }
    
    private void sendMessage(JsonObject message) throws IOException {
        String json = gson.toJson(message);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = CONTENT_LENGTH + bytes.length + "\r\n\r\n";

        log.trace("DAP send: {}", json);

        synchronized (outputStream) {
            outputStream.write(header.getBytes(StandardCharsets.UTF_8));
            outputStream.write(bytes);
            outputStream.flush();
        }
    }

    private void startReaderThread(InputStream inputStream) {
        readerThread = new Thread(() -> {
            BufferedInputStream in = new BufferedInputStream(inputStream);
            try {
                while (connected) {
                    String message = readMessage(in);
                    if (message != null) {
                        handleMessage(message);
                    }
                }
            } catch (IOException e) {
                if (connected) {
                    log.error("DAP reader error", e);
                    if (listener != null) {
                        listener.onError(e);
                    }
                }
            } finally {
                connected = false;
                failPendingRequests("Connection closed");
                if (listener != null) {
                    listener.onDisconnected("Connection closed");
                }
            }
        }, "DAP-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private String readMessage(BufferedInputStream in) throws IOException {
        // Read headers until we find Content-Length
        int contentLength = -1;

        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;  // EOF
            }
            if (line.isEmpty()) {
                break;  // End of headers
            }
            if (line.startsWith(CONTENT_LENGTH)) {
                String lengthValue = line.substring(CONTENT_LENGTH.length()).trim();
                try {
                    contentLength = Integer.parseInt(lengthValue);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid Content-Length header: '" + line + "'", e);
                }
            }
        }

        if (contentLength < 0) {
            throw new IOException("Missing Content-Length header");
        }

        // Read the body
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) {
                throw new IOException("Unexpected EOF reading message body");
            }
            read += n;
        }

        return new String(body, StandardCharsets.UTF_8);
    }

    private String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return sb.toString();
                }
                sb.append((char) c);
                if (next != -1) sb.append((char) next);
            } else if (c == '\n') {
                return sb.toString();
            } else {
                sb.append((char) c);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void handleMessage(String message) {
        log.trace("DAP recv: {}", message);

        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            String type = json.get("type").getAsString();

            switch (type) {
                case "response" -> handleResponse(json);
                case "event" -> handleEvent(json);
                default -> log.trace("Unknown DAP message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to parse DAP message: {}", message, e);
        }
    }

    private void handleResponse(JsonObject json) {
        int requestSeq = json.get("request_seq").getAsInt();
        CompletableFuture<JsonObject> future = pendingRequests.remove(requestSeq);

        if (future == null) {
            log.warn("Received response for unknown request seq: {}", requestSeq);
            return;
        }

        boolean success = json.has("success") && json.get("success").getAsBoolean();
        if (success) {
            JsonObject body = json.has("body") ? json.getAsJsonObject("body") : new JsonObject();
            future.complete(body);
        } else {
            String errorMsg = json.has("message") ? json.get("message").getAsString() : "Unknown error";
            future.completeExceptionally(new DapException(errorMsg, json));
        }
    }

    private void handleEvent(JsonObject json) {
        if (listener == null) return;

        String event = json.get("event").getAsString();
        JsonObject body = json.has("body") ? json.getAsJsonObject("body") : new JsonObject();

        switch (event) {
            case "stopped" -> listener.onStopped(body);
            case "continued" -> listener.onContinued(body);
            case "terminated" -> listener.onTerminated();
            case "output" -> listener.onOutput(body);
            case "loadedSource" -> listener.onLoadedSource(body);
            default -> log.trace("Unhandled DAP event: {}", event);
        }
    }

    private void failPendingRequests(String reason) {
        Exception error = new IllegalStateException(reason);
        for (CompletableFuture<JsonObject> future : pendingRequests.values()) {
            future.completeExceptionally(error);
        }
        pendingRequests.clear();
    }

    /**
     * Exception for DAP errors.
     */
    public static class DapException extends Exception {
        private final JsonObject errorDetails;

        public DapException(String message, JsonObject errorDetails) {
            super(message);
            this.errorDetails = errorDetails;
        }

        public JsonObject getErrorDetails() {
            return errorDetails;
        }
    }
}
