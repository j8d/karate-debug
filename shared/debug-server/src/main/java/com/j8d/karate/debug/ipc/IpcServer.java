package com.j8d.karate.debug.ipc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * IPC server for the child (Karate runner) process.
 *
 * Listens for a single client connection (the parent coordinator) and handles:
 * - Receiving commands from the parent
 * - Sending responses back
 * - Sending events to the parent
 *
 * Uses a dedicated sender thread to avoid socket contention between the
 * Karate execution thread (sending events) and the IPC reader thread.
 */
public class IpcServer {

    private static final Logger log = LoggerFactory.getLogger(IpcServer.class);

    private final Gson gson = new Gson();
    private final AtomicInteger sequenceNumber = new AtomicInteger(1);
    private final BlockingQueue<IpcMessage> sendQueue = new LinkedBlockingQueue<>();

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader reader;
    private IpcServerHandler handler;
    private Thread acceptThread;
    private Thread readerThread;
    private Thread senderThread;
    private volatile boolean running = false;
    private volatile boolean clientConnected = false;
    private int actualPort;
    
    /**
     * Sets the command handler.
     */
    public void setHandler(IpcServerHandler handler) {
        this.handler = handler;
    }
    
    /**
     * Starts the server on the specified port.
     * 
     * @param port The port to listen on (0 for automatic assignment)
     * @throws IOException if the server cannot start
     */
    public void start(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        actualPort = serverSocket.getLocalPort();
        running = true;
        
        log.debug("IPC server started on port {}", actualPort);
        
        // Start accept thread
        acceptThread = new Thread(this::acceptLoop, "IPC-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }
    
    /**
     * Returns the port the server is listening on.
     */
    public int getPort() {
        return actualPort;
    }
    
    /**
     * Stops the server.
     */
    public void stop() {
        if (!running) return;
        
        running = false;
        clientConnected = false;
        log.debug("Stopping IPC server");
        
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {
            log.debug("Error closing client socket", e);
        }
        
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            log.debug("Error closing server socket", e);
        }
    }
    
    /**
     * Sends an event to the parent process.
     * 
     * @param event The event name (from IpcEvents)
     * @param body The event body (may be null)
     */
    public void sendEvent(String event, JsonObject body) {
        if (!clientConnected) {
            log.warn("Cannot send event, no client connected: {}", event);
            return;
        }
        
        int seq = sequenceNumber.getAndIncrement();
        IpcMessage message = IpcMessage.event(seq, event, body);
        sendMessage(message);
    }
    
    /**
     * Returns true if a client is connected.
     */
    public boolean isClientConnected() {
        return clientConnected;
    }
    
    /**
     * Queues a message for sending. The dedicated sender thread will write it to the socket.
     * This avoids socket contention between multiple threads.
     */
    private void sendMessage(IpcMessage message) {
        if (!clientConnected) {
            log.warn("Cannot send message, no client connected");
            return;
        }
        log.debug("IPC TX queued [thread={}]: {}", Thread.currentThread().getName(), gson.toJson(message));
        if (!sendQueue.offer(message)) {
            log.error("Failed to enqueue IPC message, queue full; dropping message");
        }
    }

    /**
     * Sender loop that drains the queue and writes messages to the socket.
     * This runs on a dedicated thread to avoid socket contention.
     */
    private void senderLoop() {
        log.debug("IPC sender thread started");
        try {
            while (running && clientConnected) {
                // Use poll with timeout so we can log heartbeats and check loop conditions
                IpcMessage message = sendQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS);

                if (message == null) {
                    // Timeout - no message available, continue loop
                    continue;
                }

                // Double-check we should still send (connection might have closed while waiting)
                if (!running || !clientConnected) {
                    log.warn("IPC sender: connection closed after take, discarding message seq={}", message.getSeq());
                    break;
                }

                String json = gson.toJson(message);
                byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
                clientSocket.getOutputStream().write(bytes);
                clientSocket.getOutputStream().flush();
                log.trace("IPC TX: {}", json);
            }
            log.debug("IPC sender loop exited: running={}, clientConnected={}", running, clientConnected);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("IPC sender thread interrupted");
        } catch (IOException e) {
            log.error("IPC sender IO error: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("IPC sender unexpected error: {}", e.getMessage(), e);
        }
        log.debug("IPC sender thread ending, queue size={}", sendQueue.size());
    }
    
    private void sendResponse(int requestSeq, boolean success, JsonObject body) {
        int seq = sequenceNumber.getAndIncrement();
        IpcMessage response = IpcMessage.response(seq, requestSeq, success, body);
        sendMessage(response);
    }
    
    private void sendErrorResponse(int requestSeq, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        sendResponse(requestSeq, false, body);
    }
    
    /**
     * Waits for a client connection.
     */
    private void acceptLoop() {
        try {
            log.debug("Waiting for parent connection...");
            clientSocket = serverSocket.accept();

            reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            clientConnected = true;

            log.debug("Parent connected from {}", clientSocket.getRemoteSocketAddress());

            // Start sender thread (dedicated thread for writing to socket)
            senderThread = new Thread(this::senderLoop, "IPC-Sender");
            senderThread.setDaemon(true);
            senderThread.start();

            // Start reader thread
            readerThread = new Thread(this::readLoop, "IPC-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (IOException e) {
            if (running) {
                log.error("Error accepting connection", e);
            }
        }
    }

    /**
     * Reads and handles messages from the parent.
     */
    private void readLoop() {
        log.info("IPC reader thread started: {}", Thread.currentThread().getName());
        try {
            String line;
            while (running && clientConnected) {
                line = reader.readLine();
                if (line == null) {
                    log.info("IPC reader got null (EOF)");
                    break;
                }
                log.debug("IPC RX [thread={}]: {}", Thread.currentThread().getName(), line);
                handleMessage(line);
            }
            log.info("IPC reader loop exited: running={}, clientConnected={}", running, clientConnected);
        } catch (IOException e) {
            if (running && clientConnected) {
                log.error("Error reading from parent", e);
            }
        } catch (Exception e) {
            log.error("Unexpected error in IPC reader", e);
        } finally {
            clientConnected = false;
            log.info("IPC reader thread ending, parent disconnected");
        }
    }

    /**
     * Handles a received message.
     */
    private void handleMessage(String json) {
        try {
            IpcMessage message = gson.fromJson(json, IpcMessage.class);

            if (message.isRequest()) {
                handleRequest(message);
            } else {
                log.warn("Received unexpected message type from parent: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Error parsing IPC message: {}", json, e);
        }
    }

    /**
     * Handles a command request from the parent.
     */
    private void handleRequest(IpcMessage request) {
        String command = request.getCommand();
        int seq = request.getSeq();

        if (handler == null) {
            log.error("No handler registered for command: {}", command);
            sendErrorResponse(seq, "No handler registered");
            return;
        }

        try {
            JsonObject result = handler.handleCommand(command, request.getBody());
            sendResponse(seq, true, result);
        } catch (Exception e) {
            log.error("Error handling command: {}", command, e);
            sendErrorResponse(seq, e.getMessage());
        }
    }
}

