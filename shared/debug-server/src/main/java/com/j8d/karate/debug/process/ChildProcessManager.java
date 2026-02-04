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

    // Flag to track intentional shutdown (suppresses "Stream closed" errors)
    private volatile boolean stopping = false;

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
            log.debug("Child process ready: {}", processInfo);
            return processInfo;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Child process failed to start: " + e.getCause().getMessage(), e.getCause());
        }
    }
    
    /**
     * Stops the child process.
     */
    public void stop() {
        log.trace("Stopping child process...");

        // Set stopping flag FIRST to suppress "Stream closed" errors in reader threads
        stopping = true;

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

        // Build classpath - include debug server JAR for GraalVM tools if JS debugging is enabled
        String classpath = config.getClasspath();
        if (config.isJsDebuggingEnabled()) {
            String debugServerJar = getDebugServerJarPath();
            if (debugServerJar != null) {
                // Prepend debug server JAR to ensure our bundled GraalVM tools take precedence
                String pathSeparator = System.getProperty("path.separator");
                classpath = debugServerJar + pathSeparator + classpath;
                log.debug("Added debug server JAR to classpath for GraalVM tools: {}", debugServerJar);
            }
        }

        // Add classpath
        cmd.add("-cp");
        cmd.add(classpath);
        
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
        // Read stdout for IPC port discovery and forward Karate output
        outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.trace("[child stdout] {}", line);

                    // Forward Karate output to Debug Console (filter out internal messages)
                    forwardToDebugConsole(line);

                    // Look for JDWP port announcement (JVM prints this to stdout)
                    Matcher jdwpMatcher = JDWP_PORT_PATTERN.matcher(line);
                    if (jdwpMatcher.find()) {
                        try {
                            discoveredJdwpPort = Integer.parseInt(jdwpMatcher.group(1));
                            log.debug("Discovered JDWP port: {}", discoveredJdwpPort);
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse JDWP port from stdout: {}", line);
                        }
                    }

                    // Look for GraalVM DAP server port announcement (can come via stdout)
                    // Pattern: "[Graal DAP] Starting server and listening on localhost/127.0.0.1:PORT"
                    Matcher dapMatcher = DAP_PORT_PATTERN.matcher(line);
                    if (dapMatcher.find()) {
                        try {
                            discoveredDapPort = Integer.parseInt(dapMatcher.group(1));
                            log.debug("Discovered GraalVM DAP server from stdout: port={}", discoveredDapPort);

                            // Notify listener for late JavaScript backend creation
                            DapDiscoveryListener listener = dapDiscoveryListener;
                            if (listener != null) {
                                log.debug("Notifying DAP discovery listener");
                                listener.onDapPortDiscovered(discoveredDapPort);
                            } else {
                                log.trace("No DAP discovery listener registered");
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse GraalVM DAP port from stdout: {}", line);
                        }
                    }

                    // Look for IPC port announcement
                    Matcher matcher = IPC_PORT_PATTERN.matcher(line);
                    if (matcher.find()) {
                        try {
                            int ipcPort = Integer.parseInt(matcher.group(1));
                            connectToChild(ipcPort);
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse IPC port from stdout: {}", line);
                        }
                    }
                }
            } catch (IOException e) {
                // Only log error if we're not stopping (intentional shutdown)
                if (isRunning() && !stopping) {
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
                    log.trace("[child stderr] {}", line);

                    // Forward stderr to Debug Console (filter out internal messages)
                    forwardToDebugConsole(line);

                    // Look for JDWP port announcement
                    Matcher jdwpMatcher = JDWP_PORT_PATTERN.matcher(line);
                    if (jdwpMatcher.find()) {
                        try {
                            discoveredJdwpPort = Integer.parseInt(jdwpMatcher.group(1));
                            log.debug("Discovered JDWP port from stderr: {}", discoveredJdwpPort);
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse JDWP port from stderr: {}", line);
                        }
                    }

                    // Look for GraalVM DAP server port announcement
                    // Pattern: "[Graal DAP] Starting server and listening on localhost/127.0.0.1:PORT"
                    Matcher dapMatcher = DAP_PORT_PATTERN.matcher(line);
                    if (dapMatcher.find()) {
                        try {
                            discoveredDapPort = Integer.parseInt(dapMatcher.group(1));
                            log.debug("Discovered GraalVM DAP server from stderr: port={}", discoveredDapPort);

                            // Notify listener for late JavaScript backend creation
                            DapDiscoveryListener listener = dapDiscoveryListener;
                            if (listener != null) {
                                log.info("Notifying DAP discovery listener");
                                listener.onDapPortDiscovered(discoveredDapPort);
                            } else {
                                log.trace("No DAP discovery listener registered");
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse GraalVM DAP port from stderr: {}", line);
                        }
                    }
                }
            } catch (IOException e) {
                // Only log error if we're not stopping (intentional shutdown)
                if (isRunning() && !stopping) {
                    log.error("Error reading child stderr", e);
                }
            }
        }, "Child-Stderr");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    /**
     * Forwards child process output to stdout for display in both Output tab and Debug Console.
     *
     * The output flow is:
     * 1. Child process logs → child stdout
     * 2. Parent reads child stdout → this method → System.out.println() → parent stdout
     * 3. VS Code captures parent stdout → Output tab AND Debug Console (via logRaw())
     *
     * We do NOT send DAP output events here because VS Code's logRaw() already sends
     * to Debug Console, which would cause duplicates.
     */
    private void forwardToDebugConsole(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }

        // Filter out internal/infrastructure messages that aren't useful to users
        if (shouldFilterOutput(line)) {
            return;
        }

        // Print directly to stdout - VS Code captures this for both Output tab and Debug Console
        // (log.info() would be filtered when log level is set to WARN or ERROR)
        System.out.println(line);
    }

    /**
     * Determines if a line should be filtered out from Debug Console output.
     * Filters internal infrastructure messages while keeping Karate test output.
     */
    private boolean shouldFilterOutput(String line) {
        // Filter IPC protocol messages
        if (line.startsWith("IPC_PORT=") || line.contains("IPC TX") || line.contains("IPC RX")) {
            return true;
        }

        // Filter internal debug server messages (but keep useful ones like "Karate execution completed")
        if (line.contains("KarateRunner") || line.contains("IpcServer") || line.contains("RunnerCommandHandler")) {
            return true;
        }

        // Filter GraalVM/debugger infrastructure messages
        if (line.startsWith("[Graal DAP]") || line.startsWith("Debugger listening on") ||
            line.contains("devtools://devtools/bundled")) {
            return true;
        }

        // Filter JVM startup messages
        if (line.startsWith("Listening for transport dt_socket") ||
            line.contains("sun.java.command") || line.contains("polyglot.inspect")) {
            return true;
        }

        // Filter empty lines
        if (line.trim().isEmpty()) {
            return true;
        }

        return false;
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
                log.trace("IPC client connected");
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
        log.trace("handleReadyEvent: ipcPort={}", ipcPort);

        // Get ports from event body, falling back to discovered ports
        int jdwpPort = event.getBodyInt("jdwpPort", 0);
        if (jdwpPort == 0 && discoveredJdwpPort > 0) {
            jdwpPort = discoveredJdwpPort;
            log.trace("Using JDWP port discovered from stderr: {}", jdwpPort);
        }

        // Get DAP port from event body or from stderr discovery
        int jsDapPort = event.getBodyInt("jsDapPort", 0);
        String graalVmVersion = event.getBodyString("graalVmVersion");

        // Fall back to DAP port discovered from stderr (for dynamic port case)
        if (jsDapPort == 0 && discoveredDapPort > 0) {
            jsDapPort = discoveredDapPort;
            log.trace("Using GraalVM DAP port discovered from stderr: {}", jsDapPort);
        }

        ChildProcessInfo info = new ChildProcessInfo(ipcPort, jdwpPort, jsDapPort, graalVmVersion);

        log.info("Child process ready: IPC={}, JDWP={}, JS-DAP={}", ipcPort, jdwpPort, jsDapPort);

        readyFuture.complete(info);
    }

    /**
     * Gets the path to the debug server JAR file.
     * The JAR contains bundled GraalVM tools (dap-tool) needed for JavaScript debugging.
     *
     * @return the path to the JAR file, or null if it cannot be determined
     */
    private String getDebugServerJarPath() {
        try {
            // Get the location of this class (ChildProcessManager) which is in the debug server JAR
            java.security.CodeSource codeSource = ChildProcessManager.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                java.net.URL jarUrl = codeSource.getLocation();
                java.io.File jarFile = new java.io.File(jarUrl.toURI());
                if (jarFile.exists() && jarFile.isFile() && jarFile.getName().endsWith(".jar")) {
                    return jarFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.warn("Could not determine debug server JAR path", e);
        }
        return null;
    }
}

