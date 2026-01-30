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
    private static final Pattern JDWP_PORT_PATTERN = Pattern.compile("Listening for transport dt_socket at address: (\\d+)");
    // GraalVM DAP server outputs: "[Graal DAP] Starting server and listening on localhost/127.0.0.1:PORT"
    private static final Pattern DAP_PORT_PATTERN = Pattern.compile("\\[Graal DAP\\] Starting server and listening on .+:(\\d+)");
    private static final int STARTUP_TIMEOUT_SECONDS = 30;

    /**
     * Listener for GraalVM DAP port discovery events.
     * Called when the GraalVM DAP server port is discovered from stderr.
     */
    public interface DapDiscoveryListener {
        void onDapPortDiscovered(int port);
    }

    private final ChildProcessConfig config;
    private final IpcClient ipcClient;

    private Process process;
    private ChildProcessInfo processInfo;
    private CompletableFuture<ChildProcessInfo> readyFuture;
    private Thread outputThread;
    private Thread errorThread;
    private volatile DapDiscoveryListener dapDiscoveryListener;

    // Ports discovered from child process output
    private volatile int discoveredJdwpPort = 0;
    private volatile int discoveredDapPort = 0;

    public ChildProcessManager(ChildProcessConfig config) {
        this.config = config;
        this.ipcClient = new IpcClient();
    }

    /**
     * Sets a listener to be notified when the GraalVM DAP port is discovered from stderr.
     * This is called when GraalVM starts the DAP server.
     */
    public void setDapDiscoveryListener(DapDiscoveryListener listener) {
        this.dapDiscoveryListener = listener;
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
        // Log abbreviated command (full classpath is too long)
        String cmdSummary = command.stream()
            .map(arg -> arg.startsWith("-cp") || arg.contains(":") && arg.contains(".jar")
                ? (arg.startsWith("-cp") ? arg : "[classpath]")
                : arg)
            .reduce((a, b) -> a + " " + b)
            .orElse("");
        log.info("Starting child process: {}", cmdSummary.length() > 200
            ? cmdSummary.substring(0, 200) + "..."
            : cmdSummary);
        
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

        // Add GraalVM DAP server if JS debugging enabled
        // Note: dap.Suspend option is not supported in the GraalVM version bundled with Karate
        if (config.isJsDebuggingEnabled()) {
            int port = config.getJsDebugPort();
            cmd.add("-Dpolyglot.dap=" + (port > 0 ? port : "0"));
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
        if (config.getWorkingDirectory() != null) {
            cmd.add("--workspace=" + config.getWorkingDirectory().getAbsolutePath());
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

                    // Look for JDWP port announcement (JVM prints this to stdout)
                    Matcher jdwpMatcher = JDWP_PORT_PATTERN.matcher(line);
                    if (jdwpMatcher.find()) {
                        discoveredJdwpPort = Integer.parseInt(jdwpMatcher.group(1));
                        log.info("Discovered JDWP port: {}", discoveredJdwpPort);
                    }

                    // Look for GraalVM DAP server port announcement (can come via stdout)
                    // Pattern: "[Graal DAP] Starting server and listening on localhost/127.0.0.1:PORT"
                    Matcher dapMatcher = DAP_PORT_PATTERN.matcher(line);
                    if (dapMatcher.find()) {
                        discoveredDapPort = Integer.parseInt(dapMatcher.group(1));
                        log.info("Discovered GraalVM DAP server from stdout: port={}", discoveredDapPort);

                        // Notify listener for late JavaScript backend creation
                        DapDiscoveryListener listener = dapDiscoveryListener;
                        if (listener != null) {
                            log.info("Notifying DAP discovery listener");
                            listener.onDapPortDiscovered(discoveredDapPort);
                        } else {
                            log.debug("No DAP discovery listener registered");
                        }
                    }

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

                    // Look for JDWP port announcement
                    Matcher jdwpMatcher = JDWP_PORT_PATTERN.matcher(line);
                    if (jdwpMatcher.find()) {
                        discoveredJdwpPort = Integer.parseInt(jdwpMatcher.group(1));
                        log.info("Discovered JDWP port from stderr: {}", discoveredJdwpPort);
                    }

                    // Look for GraalVM DAP server port announcement
                    // Pattern: "[Graal DAP] Starting server and listening on localhost/127.0.0.1:PORT"
                    Matcher dapMatcher = DAP_PORT_PATTERN.matcher(line);
                    if (dapMatcher.find()) {
                        discoveredDapPort = Integer.parseInt(dapMatcher.group(1));
                        log.info("Discovered GraalVM DAP server from stderr: port={}", discoveredDapPort);

                        // Notify listener for late JavaScript backend creation
                        DapDiscoveryListener listener = dapDiscoveryListener;
                        if (listener != null) {
                            log.info("Notifying DAP discovery listener");
                            listener.onDapPortDiscovered(discoveredDapPort);
                        } else {
                            log.debug("No DAP discovery listener registered");
                        }
                    }
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

        // Get ports from event body, falling back to discovered ports
        int jdwpPort = event.getBodyInt("jdwpPort", 0);
        if (jdwpPort == 0 && discoveredJdwpPort > 0) {
            jdwpPort = discoveredJdwpPort;
            log.debug("Using JDWP port discovered from stderr: {}", jdwpPort);
        }

        // Get DAP port from event body or from stderr discovery
        int jsDapPort = event.getBodyInt("jsDapPort", 0);
        String graalVmVersion = event.getBodyString("graalVmVersion");

        // Fall back to DAP port discovered from stderr (for dynamic port case)
        if (jsDapPort == 0 && discoveredDapPort > 0) {
            jsDapPort = discoveredDapPort;
            log.debug("Using GraalVM DAP port discovered from stderr: {}", jsDapPort);
        }

        ChildProcessInfo info = new ChildProcessInfo(ipcPort, jdwpPort, jsDapPort, graalVmVersion);

        log.info("Child process ready: IPC={}, JDWP={}, JS-DAP={}", ipcPort, jdwpPort, jsDapPort);

        readyFuture.complete(info);
    }
}

