package com.j8d.karate.debug.runner;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.j8d.karate.debug.ipc.IpcEvents;
import com.j8d.karate.debug.ipc.IpcServer;

/**
 * Main class for the child Karate runner process.
 * 
 * This process:
 * 1. Starts an IPC server and prints the port to stdout
 * 2. Discovers debug agent ports (JDWP, Chrome Inspector)
 * 3. Sends "ready" event with all port information
 * 4. Handles debug commands via IPC
 * 5. Runs Karate tests with RuntimeHook for debugging
 */
public class KarateRunner {
    
    private static final Logger log = LoggerFactory.getLogger(KarateRunner.class);
    private static final Gson gson = new Gson();
    
    private final IpcServer ipcServer;
    private final RunnerCommandHandler commandHandler;

    private String featurePath;
    private int featureLine = -1;
    private String karateEnv;
    private String workspaceRoot;
    private String logLevel = "INFO";
    
    public KarateRunner() {
        this.ipcServer = new IpcServer();
        this.commandHandler = new RunnerCommandHandler(this);
    }
    
    public static void main(String[] args) {
        KarateRunner runner = new KarateRunner();
        runner.parseArgs(args);

        // Apply log level before starting (must be done after parsing args)
        setLogLevel(runner.logLevel);

        try {
            runner.start();
        } catch (Exception e) {
            log.error("Failed to start KarateRunner", e);
            System.exit(1);
        }
    }

    private static void setLogLevel(String levelName) {
        Level level = switch (levelName.toLowerCase()) {
            case "error" -> Level.ERROR;
            case "warn" -> Level.WARN;
            case "info" -> Level.INFO;
            case "debug" -> Level.DEBUG;
            case "trace" -> Level.TRACE;
            default -> Level.INFO;
        };

        // Set ROOT logger level - this affects all loggers
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(level);

        // Set log level for our debug server classes
        ch.qos.logback.classic.Logger debugLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.j8d.karate.debug");
        debugLogger.setLevel(level);

        // Set log level for Karate framework
        ch.qos.logback.classic.Logger karateLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.intuit.karate");
        karateLogger.setLevel(level);
    }
    
    private void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--feature=")) {
                String path = arg.substring("--feature=".length());
                // Parse line number if present (e.g., /path/to/feature.feature:23)
                if (path.contains(":")) {
                    int colonIdx = path.lastIndexOf(':');
                    String possibleLineNum = path.substring(colonIdx + 1);
                    try {
                        featureLine = Integer.parseInt(possibleLineNum);
                        featurePath = path.substring(0, colonIdx);
                    } catch (NumberFormatException e) {
                        featurePath = path;
                    }
                } else {
                    featurePath = path;
                }
            } else if (arg.startsWith("--env=")) {
                karateEnv = arg.substring("--env=".length());
            } else if (arg.startsWith("--workspace=")) {
                workspaceRoot = arg.substring("--workspace=".length());
            } else if (arg.startsWith("--log-level=")) {
                logLevel = arg.substring("--log-level=".length());
            }
        }
    }
    
    private void start() throws IOException {
        log.info("KarateRunner starting...");
        
        // Start IPC server on random port
        ipcServer.setHandler(commandHandler);
        ipcServer.start(0);
        int ipcPort = ipcServer.getPort();
        
        // Print IPC port to stdout for parent to discover
        System.out.println("IPC_PORT=" + ipcPort);
        System.out.flush();
        
        log.debug("IPC server started on port {}", ipcPort);

        // Wait for parent to connect, then send ready event
        waitForConnectionAndSendReady();

        // Keep running until stopped
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.debug("Shutdown hook triggered");
            ipcServer.stop();
        }));
        
        // Block main thread
        synchronized (this) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void waitForConnectionAndSendReady() {
        // Wait for client connection in background
        new Thread(() -> {
            while (!ipcServer.isClientConnected()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            
            // Discover debug agent ports and send ready event
            sendReadyEvent();
        }, "Ready-Sender").start();
    }
    
    private void sendReadyEvent() {
        JsonObject body = new JsonObject();
        
        // Discover JDWP port (if enabled)
        int jdwpPort = discoverJdwpPort();
        if (jdwpPort > 0) {
            body.addProperty("jdwpPort", jdwpPort);
        }
        
        // Discover Chrome Inspector (if enabled)
        CdpInfo cdpInfo = discoverCdpInfo();
        if (cdpInfo != null) {
            body.addProperty("cdpPort", cdpInfo.port);
            body.addProperty("cdpWebSocketUrl", cdpInfo.webSocketUrl);
            if (cdpInfo.graalVmVersion != null) {
                body.addProperty("graalVmVersion", cdpInfo.graalVmVersion);
            }
        }
        
        log.debug("Sending ready event: {}", body);
        ipcServer.sendEvent(IpcEvents.READY, body);
    }
    
    // Getters for command handler and debugger
    String getFeaturePath() { return featurePath; }
    int getFeatureLine() { return featureLine; }
    String getKarateEnv() { return karateEnv; }
    String getWorkspaceRoot() { return workspaceRoot; }
    String getLogLevel() { return logLevel; }
    IpcServer getIpcServer() { return ipcServer; }
    
    void shutdown() {
        synchronized (this) {
            notifyAll();
        }
    }
    
    /**
     * Discovers the JDWP port by checking system properties.
     * When JDWP is enabled with address=*:0, the actual port is assigned dynamically.
     * We can find it via the management interface or by parsing agent output.
     */
    private int discoverJdwpPort() {
        // Check if JDWP agent is loaded by looking for the debug agent property
        String jdwpAddress = System.getProperty("sun.jdwp.listenerAddress");
        log.debug("sun.jdwp.listenerAddress = {}", jdwpAddress);

        if (jdwpAddress != null && jdwpAddress.contains(":")) {
            try {
                String portStr = jdwpAddress.substring(jdwpAddress.lastIndexOf(':') + 1);
                int port = Integer.parseInt(portStr);
                log.debug("Discovered JDWP port: {}", port);
                return port;
            } catch (NumberFormatException e) {
                log.warn("Could not parse JDWP port from: {}", jdwpAddress);
            }
        }

        // Fallback: check for JDWP in command line args
        String javaCmd = System.getProperty("sun.java.command");
        log.debug("sun.java.command = {}", javaCmd);

        return 0;
    }

    /**
     * Discovers Chrome DevTools Protocol info by querying the inspector endpoint.
     */
    private CdpInfo discoverCdpInfo() {
        String inspectProp = System.getProperty("polyglot.inspect");
        log.debug("polyglot.inspect = {}", inspectProp);

        if (inspectProp == null) {
            log.debug("Chrome Inspector not enabled (polyglot.inspect not set)");
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(inspectProp);
        } catch (NumberFormatException e) {
            // Might be "true" or other value, try default port
            port = 9229;
        }

        // Port 0 means dynamic port - we need to discover it
        if (port == 0) {
            log.debug("Chrome Inspector using dynamic port, attempting discovery...");
            // Try common ports or wait for inspector to start
            // For now, we can't easily discover the dynamic port
            // The inspector binds to a random port but doesn't expose it via system property
            log.warn("Dynamic Chrome Inspector port (0) not yet supported for discovery");
            return null;
        }

        log.debug("Querying Chrome Inspector at port {}", port);

        // Query the inspector's /json endpoint
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);

            int responseCode = conn.getResponseCode();
            log.debug("Chrome Inspector /json response code: {}", responseCode);

            if (responseCode == 200) {
                try (Scanner scanner = new Scanner(conn.getInputStream())) {
                    String json = scanner.useDelimiter("\\A").next();
                    log.debug("Chrome Inspector /json response: {}", json);
                    JsonArray targets = gson.fromJson(json, JsonArray.class);

                    if (targets.size() > 0) {
                        JsonObject target = targets.get(0).getAsJsonObject();
                        String wsUrl = target.has("webSocketDebuggerUrl")
                            ? target.get("webSocketDebuggerUrl").getAsString()
                            : null;

                        // Try to get GraalVM version from description
                        String description = target.has("description")
                            ? target.get("description").getAsString()
                            : null;

                        log.debug("Discovered CDP: port={}, wsUrl={}", port, wsUrl);
                        return new CdpInfo(port, wsUrl, description);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not discover CDP info: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Chrome DevTools Protocol connection info.
     */
    private static class CdpInfo {
        final int port;
        final String webSocketUrl;
        final String graalVmVersion;

        CdpInfo(int port, String webSocketUrl, String graalVmVersion) {
            this.port = port;
            this.webSocketUrl = webSocketUrl;
            this.graalVmVersion = graalVmVersion;
        }
    }
}

