package com.j8d.karate.intellij.debug;

import com.intellij.openapi.application.PathManager;
import com.j8d.karate.intellij.run.KarateRunConfiguration;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for communicating with the Karate DAP (Debug Adapter Protocol) server.
 * Bridges IntelliJ's XDebugger to the DAP server.
 */
public class KarateDapClient {
    
    private final KarateDebugProcess debugProcess;
    private Process serverProcess;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int requestSeq = 1;
    
    public KarateDapClient(KarateDebugProcess debugProcess) {
        this.debugProcess = debugProcess;
    }
    
    public void start(KarateRunConfiguration configuration) throws Exception {
        // Find the debug server JAR
        String jarPath = findDebugServerJar();
        if (jarPath == null) {
            throw new Exception("Could not find karate-debug-server.jar");
        }
        
        debugProcess.log("Using debug server: " + jarPath);
        
        // Find a free port
        int port = findFreePort();
        debugProcess.log("Starting DAP server on port " + port);
        
        // Start the debug server process
        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-jar", jarPath,
            "--port", String.valueOf(port),
            "--feature", configuration.getFeatureFile(),
            "--env", configuration.getKarateEnv()
        );
        
        pb.redirectErrorStream(true);
        serverProcess = pb.start();
        
        // Log server output
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    debugProcess.log("[DAP] " + line);
                }
            } catch (IOException e) {
                // Server closed
            }
        }).start();
        
        // Wait for server to start
        Thread.sleep(2000);
        
        // Connect to the server
        socket = new Socket("localhost", port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        
        running.set(true);
        debugProcess.log("Connected to DAP server");
        
        // Send initialize request
        sendInitialize();
        
        // Start message reading loop
        startMessageLoop();
    }
    
    private String findDebugServerJar() {
        // Try plugin resources first
        Path pluginPath = Path.of(PathManager.getPluginsPath(), 
            "karate-debug-intellij", "lib", "karate-debug-server-1.0.0.jar");
        if (Files.exists(pluginPath)) {
            return pluginPath.toString();
        }
        
        // Try development path (monorepo structure)
        Path devPath = Path.of(System.getProperty("user.dir"), 
            "..", "shared", "debug-server", "target", "karate-debug-server-1.0.0.jar");
        if (Files.exists(devPath)) {
            return devPath.toAbsolutePath().normalize().toString();
        }
        
        return null;
    }
    
    private int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
    
    private void sendInitialize() throws IOException {
        // TODO: Implement proper DAP initialize request
        debugProcess.log("Sending DAP initialize request...");
    }
    
    private void startMessageLoop() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    // TODO: Read and parse DAP messages
                    Thread.sleep(100);
                } catch (Exception e) {
                    if (running.get()) {
                        debugProcess.log("Error reading DAP message: " + e.getMessage());
                    }
                }
            }
        }).start();
    }
    
    public void sendStepOver() {
        debugProcess.log("Step Over");
        // TODO: Send DAP next request
    }
    
    public void sendStepInto() {
        debugProcess.log("Step Into");
        // TODO: Send DAP stepIn request
    }
    
    public void sendStepOut() {
        debugProcess.log("Step Out");
        // TODO: Send DAP stepOut request
    }
    
    public void sendContinue() {
        debugProcess.log("Continue");
        // TODO: Send DAP continue request
    }
    
    public void setBreakpoint(String filePath, int line) {
        debugProcess.log("Set breakpoint: " + filePath + ":" + line);
        // TODO: Send DAP setBreakpoints request
    }
    
    public void removeBreakpoint(String filePath, int line) {
        debugProcess.log("Remove breakpoint: " + filePath + ":" + line);
        // TODO: Send DAP setBreakpoints request
    }
    
    public void stop() {
        running.set(false);
        
        try {
            if (socket != null) socket.close();
            if (serverProcess != null) serverProcess.destroyForcibly();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}

