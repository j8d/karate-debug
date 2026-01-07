package com.j8d.karate.debug;

import ch.qos.logback.classic.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Main entry point for the Karate Debug Server.
 * Implements the Debug Adapter Protocol (DAP) for VS Code integration.
 */
public class DebugServer {
    private static final Logger logger = LoggerFactory.getLogger(DebugServer.class);
    
    private final int port;
    private final String workspaceRoot;
    private final String karateEnv;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public DebugServer(int port, String workspaceRoot, String karateEnv) {
        this.port = port;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        
        logger.info("Karate Debug Server started on port {}", port);
        logger.info("Workspace: {}", workspaceRoot);
        logger.info("Environment: {}", karateEnv);
        
        while (running) {
            try {
                logger.trace("Waiting for debugger connection...");
                Socket clientSocket = serverSocket.accept();
                logger.trace("Debugger connected from {}", clientSocket.getRemoteSocketAddress());

                // Handle the debug session
                DapSession session = new DapSession(clientSocket, workspaceRoot, karateEnv);
                boolean hadValidSession = session.run();

                if (hadValidSession) {
                    logger.trace("Debug session completed");
                    break;  // Exit after a valid DAP session
                } else {
                    logger.trace("Probe connection, waiting for next...");
                }
            } catch (IOException e) {
                if (running) {
                    logger.error("Error accepting connection", e);
                }
            }
        }
        stop();
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error("Error closing server socket", e);
            }
        }
    }

    public static void main(String[] args) {
        int port = 0;
        String workspaceRoot = System.getProperty("user.dir");
        String karateEnv = "dev";
        String logLevel = "info";

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> {
                    if (i + 1 < args.length) {
                        port = Integer.parseInt(args[++i]);
                    }
                }
                case "--workspace", "-w" -> {
                    if (i + 1 < args.length) {
                        workspaceRoot = args[++i];
                    }
                }
                case "--env", "-e" -> {
                    if (i + 1 < args.length) {
                        karateEnv = args[++i];
                    }
                }
                case "--log-level", "-l" -> {
                    if (i + 1 < args.length) {
                        logLevel = args[++i];
                    }
                }
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
            }
        }

        // Set log level before anything else
        setLogLevel(logLevel);

        // Also check system properties
        if (port == 0) {
            String portProp = System.getProperty("debug.port");
            if (portProp != null) {
                port = Integer.parseInt(portProp);
            }
        }

        if (port == 0) {
            System.err.println("Error: Port is required");
            printUsage();
            System.exit(1);
        }

        try {
            DebugServer server = new DebugServer(port, workspaceRoot, karateEnv);

            // Handle shutdown gracefully
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            server.start();
        } catch (Exception e) {
            logger.error("Failed to start debug server", e);
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

        // Set log level for our debug server classes
        ch.qos.logback.classic.Logger debugLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.j8d.karate.debug");
        debugLogger.setLevel(level);
    }

    private static void printUsage() {
        System.out.println("Karate Debug Server");
        System.out.println("Usage: java -jar karate-debug-server.jar [options]");
        System.out.println("Options:");
        System.out.println("  -p, --port <port>       Debug server port (required)");
        System.out.println("  -w, --workspace <path>  Workspace root directory");
        System.out.println("  -e, --env <env>         Karate environment (default: dev)");
        System.out.println("  -l, --log-level <level> Log level: error, warn, info, debug, trace (default: info)");
        System.out.println("  -h, --help              Show this help");
    }
}

