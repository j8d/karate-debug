package com.j8d.karate.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple DAP client for integration testing.
 * Sends DAP requests and receives responses/events.
 */
public class DapTestClient implements AutoCloseable {
    private static final Gson gson = new Gson();
    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream output;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private final Thread readerThread;

    public DapTestClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.output = socket.getOutputStream();
        
        // Start reader thread
        this.readerThread = new Thread(this::readLoop, "DAP-Reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    private void readLoop() {
        try {
            while (running) {
                String message = readMessage();
                if (message == null) break;
                
                JsonObject json = gson.fromJson(message, JsonObject.class);
                String type = json.get("type").getAsString();
                
                System.out.println("[DAP RX] " + message);
                
                if ("response".equals(type)) {
                    int requestSeq = json.get("request_seq").getAsInt();
                    CompletableFuture<JsonObject> future = pendingRequests.remove(requestSeq);
                    if (future != null) {
                        future.complete(json);
                    }
                } else if ("event".equals(type)) {
                    events.offer(json);
                }
            }
        } catch (IOException e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    private String readMessage() throws IOException {
        // Read Content-Length header
        String line;
        int contentLength = -1;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        if (contentLength <= 0) return null;

        // Read body
        char[] body = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = reader.read(body, read, contentLength - read);
            if (n < 0) return null;
            read += n;
        }
        return new String(body);
    }

    public JsonObject sendRequest(String command, JsonObject arguments) throws Exception {
        int seqNum = seq.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("seq", seqNum);
        request.addProperty("type", "request");
        request.addProperty("command", command);
        if (arguments != null) {
            request.add("arguments", arguments);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(seqNum, future);

        String json = gson.toJson(request);
        System.out.println("[DAP TX] " + json);
        
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + bytes.length + "\r\n\r\n";
        synchronized (output) {
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.flush();
        }

        return future.get(30, TimeUnit.SECONDS);
    }

    public JsonObject waitForEvent(String eventName, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonObject event = events.poll(100, TimeUnit.MILLISECONDS);
            if (event != null && eventName.equals(event.get("event").getAsString())) {
                return event;
            } else if (event != null) {
                // Put non-matching events back (not ideal but simple)
                events.offer(event);
            }
        }
        return null;
    }

    @Override
    public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
        readerThread.interrupt();
    }
}

