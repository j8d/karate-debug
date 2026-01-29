package com.j8d.karate.debug.ipc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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
 * IPC client for communication with the child (Karate runner) process.
 * 
 * The client connects to the child's IPC server via TCP socket and handles:
 * - Sending commands (requests) to the child
 * - Receiving responses correlated by sequence number
 * - Receiving events from the child
 * 
 * Thread-safe: can be used from multiple threads.
 */
public class IpcClient {
    
    private static final Logger log = LoggerFactory.getLogger(IpcClient.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    
    private final Gson gson = new Gson();
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<IpcMessage>> pendingRequests = new ConcurrentHashMap<>();
    
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private IpcClientListener listener;
    private volatile boolean connected = false;
    
    /**
     * Sets the event listener for this client.
     */
    public void setListener(IpcClientListener listener) {
        this.listener = listener;
    }
    
    /**
     * Connects to the child process IPC server.
     * 
     * @param host The host to connect to (usually "localhost")
     * @param port The port to connect to
     * @throws IOException if connection fails
     */
    public void connect(String host, int port) throws IOException {
        log.info("Connecting to IPC server at {}:{}", host, port);
        
        socket = new Socket(host, port);
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        connected = true;
        
        // Start reader thread
        readerThread = new Thread(this::readLoop, "IPC-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        
        log.info("Connected to IPC server");
        if (listener != null) {
            listener.onConnected();
        }
    }
    
    /**
     * Disconnects from the child process.
     */
    public void disconnect() {
        if (!connected) return;
        
        connected = false;
        log.info("Disconnecting from IPC server");
        
        // Cancel all pending requests
        for (CompletableFuture<IpcMessage> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("Connection closed"));
        }
        pendingRequests.clear();
        
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            log.debug("Error closing socket", e);
        }
        
        if (listener != null) {
            listener.onDisconnected("Client disconnected");
        }
    }
    
    /**
     * Sends a command to the child process and waits for the response.
     * 
     * @param command The command name
     * @param body The command body (may be null)
     * @return CompletableFuture that completes with the response
     */
    public CompletableFuture<IpcMessage> sendCommand(String command, JsonObject body) {
        return sendCommand(command, body, DEFAULT_TIMEOUT_SECONDS);
    }
    
    /**
     * Sends a command to the child process and waits for the response.
     * 
     * @param command The command name
     * @param body The command body (may be null)
     * @param timeoutSeconds Timeout in seconds
     * @return CompletableFuture that completes with the response
     */
    public CompletableFuture<IpcMessage> sendCommand(String command, JsonObject body, int timeoutSeconds) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IOException("Not connected"));
        }
        
        int seq = sequenceNumber.getAndIncrement();
        IpcMessage request = IpcMessage.request(seq, command, body);
        
        CompletableFuture<IpcMessage> future = new CompletableFuture<>();
        pendingRequests.put(seq, future);
        
        // Set timeout
        future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .whenComplete((result, error) -> pendingRequests.remove(seq));
        
        sendMessage(request);
        return future;
    }
    
    /**
     * Sends a message without waiting for a response (fire-and-forget).
     */
    private synchronized void sendMessage(IpcMessage message) {
        String json = gson.toJson(message);
        log.debug("IPC TX [thread={}]: {}", Thread.currentThread().getName(), json);

        try {
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            log.trace("IPC TX flushed [thread={}], wrote {} bytes", Thread.currentThread().getName(), bytes.length);
        } catch (IOException e) {
            log.error("IPC TX ERROR: Failed to write to socket", e);
        }
    }
    
    public boolean isConnected() {
        return connected;
    }

    /**
     * The main read loop that runs in a background thread.
     */
    private void readLoop() {
        log.info("Parent IPC reader thread started: {}", Thread.currentThread().getName());
        try {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                log.debug("Parent IPC RX [thread={}]: {}", Thread.currentThread().getName(), line);
                handleMessage(line);
                log.debug("Parent IPC reader finished handling message");
            }
            log.info("Parent IPC reader loop exited: connected={}", connected);
        } catch (IOException e) {
            if (connected) {
                log.error("Error reading from IPC server", e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        } finally {
            log.info("Parent IPC reader thread ending");
            if (connected) {
                connected = false;
                if (listener != null) {
                    listener.onDisconnected("Connection closed by server");
                }
            }
        }
    }

    /**
     * Handles a received message.
     */
    private void handleMessage(String json) {
        try {
            IpcMessage message = gson.fromJson(json, IpcMessage.class);

            if (message.isResponse()) {
                // Complete the pending request
                Integer requestSeq = message.getRequestSeq();
                if (requestSeq != null) {
                    CompletableFuture<IpcMessage> future = pendingRequests.remove(requestSeq);
                    if (future != null) {
                        if (Boolean.TRUE.equals(message.getSuccess())) {
                            future.complete(message);
                        } else {
                            String error = message.getBodyString("message");
                            future.completeExceptionally(new IpcException(error != null ? error : "Unknown error"));
                        }
                    } else {
                        log.warn("Received response for unknown request: {}", requestSeq);
                    }
                }
            } else if (message.isEvent()) {
                // Dispatch event to listener
                if (listener != null) {
                    listener.onEvent(message);
                }
            } else {
                log.warn("Received unexpected message type: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Error parsing IPC message: {}", json, e);
            if (listener != null) {
                listener.onError(e);
            }
        }
    }

    /**
     * Exception thrown when an IPC command fails.
     */
    public static class IpcException extends RuntimeException {
        public IpcException(String message) {
            super(message);
        }
    }
}

