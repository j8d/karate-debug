package com.j8d.karate.debug.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.j8d.karate.debug.ipc.IpcClient;
import com.j8d.karate.debug.ipc.IpcClientListener;
import com.j8d.karate.debug.ipc.IpcEvents;
import com.j8d.karate.debug.ipc.IpcMessage;

/**
 * Manages the child Karate runner process.
 * 
 * Responsibilities:
 * - Spawns the child JVM with appropriate debug agents
 * - Discovers the IPC port from child's stdout
 * - Connects IPC client to child
 * - Waits for "ready" event with full port information
 * - Provides access to child process info
 */
public class ChildProcessManager {
    
    private static final Logger log = LoggerFactory.getLogger(ChildProcessManager.class);
    private static final Pattern IPC_PORT_PATTERN = Pattern.compile("IPC_PORT=(\\d+)");
    private static final int STARTUP_TIMEOUT_SECONDS = 30;
    
    private final ChildProcessConfig config;
    private final IpcClient ipcClient;
    
    private Process process;
    private ChildProcessInfo processInfo;
    private CompletableFuture<ChildProcessInfo> readyFuture;
    private Thread outputThread;
    private Thread errorThread;
    
    public ChildProcessManager(ChildProcessConfig config) {
        this.config = config;
        this.ipcClient = new IpcClient();
    }
    
    /**
     * Starts the child process and waits for it to be ready.
     * 
     * @return ChildProcessInfo with discovered ports
     * @throws IOException if process cannot be started
     * @throws TimeoutException if child doesn't become ready in time
     */
    public ChildProcessInfo start() throws IOException, TimeoutException, InterruptedException {
        log.info("Starting child process...");
        
        readyFuture = new CompletableFuture<>();
        
        // Build command line
        List<String> command = buildCommand();
        log.debug("Command: {}", String.join(" ", command));
        
        // Start process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(config.getWorkingDirectory());
        pb.redirectErrorStream(false);
        process = pb.start();
        
        // Start output readers
        startOutputReaders();
        
        // Wait for ready event
        try {
            processInfo = readyFuture.get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Child process ready: {}", processInfo);
            return processInfo;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Child process failed to start: " + e.getCause().getMessage(), e.getCause());
        }
    }
    
    /**
     * Stops the child process.
     */
    public void stop() {
        log.info("Stopping child process...");
        
        ipcClient.disconnect();
        
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Returns the IPC client for communicating with the child.
     */
    public IpcClient getIpcClient() {
        return ipcClient;
    }
    
    /**
     * Returns the child process info (available after start() completes).
     */
    public ChildProcessInfo getProcessInfo() {
        return processInfo;
    }
    
    /**
     * Returns true if the child process is running.
     */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }
    
    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.getJavaPath());
        
        // Add JVM args
        cmd.addAll(config.getJvmArgs());
        
        // Add JDWP agent if Java debugging enabled
        if (config.isJavaDebuggingEnabled()) {
            int port = config.getJdwpPort();
            String portSpec = port > 0 ? String.valueOf(port) : "*:0";
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + portSpec);
        }
        
        // Add Chrome Inspector if JS debugging enabled
        if (config.isJsDebuggingEnabled()) {
            int port = config.getJsDebugPort();
            cmd.add("-Dpolyglot.inspect=" + (port > 0 ? port : "0"));
            cmd.add("-Dpolyglot.inspect.Suspend=false");
        }
        
        // Add classpath
        cmd.add("-cp");
        cmd.add(config.getClasspath());
        
        // Main class - KarateRunner
        cmd.add("com.j8d.karate.debug.runner.KarateRunner");
        
        // Arguments
        if (config.getFeaturePath() != null) {
            cmd.add("--feature=" + config.getFeaturePath());
        }
        if (config.getKarateEnv() != null) {
            cmd.add("--env=" + config.getKarateEnv());
        }
        cmd.add("--log-level=" + config.getLogLevel());
        
        return cmd;
    }
    
    private void startOutputReaders() {
        // Read stdout for IPC port discovery
        outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[child stdout] {}", line);

                    // Look for IPC port announcement
                    Matcher matcher = IPC_PORT_PATTERN.matcher(line);
                    if (matcher.find()) {
                        int ipcPort = Integer.parseInt(matcher.group(1));
                        connectToChild(ipcPort);
                    }
                }
            } catch (IOException e) {
                if (isRunning()) {
                    log.error("Error reading child stdout", e);
                }
            }
        }, "Child-Stdout");
        outputThread.setDaemon(true);
        outputThread.start();

        // Read stderr
        errorThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[child stderr] {}", line);
                }
            } catch (IOException e) {
                if (isRunning()) {
                    log.error("Error reading child stderr", e);
                }
            }
        }, "Child-Stderr");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    private void connectToChild(int ipcPort) {
        log.info("Discovered IPC port: {}, connecting...", ipcPort);

        ipcClient.setListener(new IpcClientListener() {
            @Override
            public void onEvent(IpcMessage event) {
                if (IpcEvents.READY.equals(event.getEvent())) {
                    handleReadyEvent(ipcPort, event);
                }
            }

            @Override
            public void onConnected() {
                log.debug("IPC client connected");
            }

            @Override
            public void onDisconnected(String reason) {
                log.info("IPC client disconnected: {}", reason);
            }

            @Override
            public void onError(Exception error) {
                log.error("IPC client error", error);
                if (!readyFuture.isDone()) {
                    readyFuture.completeExceptionally(error);
                }
            }
        });

        try {
            ipcClient.connect("localhost", ipcPort);
        } catch (IOException e) {
            log.error("Failed to connect to child IPC server", e);
            readyFuture.completeExceptionally(e);
        }
    }

    private void handleReadyEvent(int ipcPort, IpcMessage event) {
        log.debug("Received ready event: {}", event);

        int jdwpPort = event.getBodyInt("jdwpPort", 0);
        int cdpPort = event.getBodyInt("cdpPort", 0);
        String cdpWebSocketUrl = event.getBodyString("cdpWebSocketUrl");
        String graalVmVersion = event.getBodyString("graalVmVersion");

        ChildProcessInfo info = new ChildProcessInfo(
            ipcPort, jdwpPort, cdpPort, cdpWebSocketUrl, graalVmVersion);

        readyFuture.complete(info);
    }
}

