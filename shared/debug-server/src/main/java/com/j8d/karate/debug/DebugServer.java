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
    private final String classpath;
    private final String logLevel;
    private final boolean polyglotMode;
    private final String sourcePaths;  // Semicolon-separated list of source directories/archives
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public DebugServer(int port, String workspaceRoot, String karateEnv, String classpath, String logLevel, boolean polyglotMode, String sourcePaths) {
        this.port = port;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
        this.classpath = classpath;
        this.logLevel = logLevel;
        this.polyglotMode = polyglotMode;
        this.sourcePaths = sourcePaths;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        logger.info("Karate Debug Server started on port {}", port);
        logger.info("Workspace: {}", workspaceRoot);
        logger.info("Environment: {}", karateEnv);
        logger.info("Mode: {}", polyglotMode ? "polyglot" : "standard");

        while (running) {
            try {
                logger.trace("Waiting for debugger connection...");
                Socket clientSocket = serverSocket.accept();
                logger.trace("Debugger connected from {}", clientSocket.getRemoteSocketAddress());

                // Handle the debug session
                boolean hadValidSession;
                if (polyglotMode) {
                    PolyglotDapSession session = new PolyglotDapSession(clientSocket, workspaceRoot, karateEnv, classpath, logLevel, sourcePaths);
                    hadValidSession = session.run();
                } else {
                    DapSession session = new DapSession(clientSocket, workspaceRoot, karateEnv);
                    hadValidSession = session.run();
                }

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
        String classpath = null;
        String sourcePaths = null;
        boolean polyglotMode = false;

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
                case "--classpath", "-cp" -> {
                    if (i + 1 < args.length) {
                        classpath = args[++i];
                    }
                }
                case "--source-paths" -> {
                    if (i + 1 < args.length) {
                        sourcePaths = args[++i];
                    }
                }
                case "--polyglot" -> {
                    polyglotMode = true;
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
            DebugServer server = new DebugServer(port, workspaceRoot, karateEnv, classpath, logLevel, polyglotMode, sourcePaths);

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

        // Set ROOT logger level first - this affects all loggers
        ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(level);

        // Set log level for our debug server classes
        ch.qos.logback.classic.Logger debugLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.j8d.karate.debug");
        debugLogger.setLevel(level);

        // Set log level for Karate framework (HTTP request/response logs)
        ch.qos.logback.classic.Logger karateLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.intuit.karate");
        karateLogger.setLevel(level);

        // Suppress harmless GraalVM DAP shutdown errors
        // These "Socket closed" errors occur during normal shutdown when the client closes
        // the socket before the server finishes sending final responses - they're not real errors
        suppressDapShutdownErrors();
    }

    /**
     * Suppress harmless "Socket closed" errors from GraalVM DAP library during shutdown.
     * These errors occur when the debug client closes the connection before the DAP server
     * finishes sending its final disconnect response - this is a normal race condition
     * during shutdown and not indicative of any actual problem.
     *
     * The GraalVM DAP library uses java.util.logging (JUL), not SLF4J/Logback, so we need
     * to configure JUL directly with a filter.
     */
    public static void suppressDapShutdownErrors() {
        java.util.logging.Logger dapLogger = java.util.logging.Logger.getLogger("dap");
        dapLogger.setFilter(record -> {
            String message = record.getMessage();
            Throwable thrown = record.getThrown();

            // Suppress "Socket closed" and "Broken pipe" errors during disconnect
            if (message != null && (message.contains("Socket closed") || message.contains("Broken pipe"))) {
                return false;
            }

            // Suppress exceptions that contain "Socket closed" or "Broken pipe" in their message or cause chain
            if (thrown != null) {
                Throwable current = thrown;
                while (current != null) {
                    String exMessage = current.getMessage();
                    if (exMessage != null && (exMessage.contains("Socket closed") || exMessage.contains("Broken pipe"))) {
                        return false;
                    }
                    // Also check the exception class name
                    if (current instanceof java.net.SocketException) {
                        return false;
                    }
                    current = current.getCause();
                }
            }

            // Allow all other log messages
            return true;
        });
    }

    private static void printUsage() {
        System.out.println("Karate Debug Server");
        System.out.println("Usage: java -jar karate-debug-server.jar [options]");
        System.out.println("Options:");
        System.out.println("  -p, --port <port>       Debug server port (required)");
        System.out.println("  -w, --workspace <path>  Workspace root directory");
        System.out.println("  -e, --env <env>         Karate environment (default: dev)");
        System.out.println("  -l, --log-level <level> Log level: error, warn, info, debug, trace (default: info)");
        System.out.println("  -cp, --classpath <cp>   Classpath for child process (polyglot mode only)");
        System.out.println("  --source-paths <paths>  Semicolon-separated list of Java source directories/archives");
        System.out.println("  --polyglot              Enable polyglot debugging (Karate + JS + Java)");
        System.out.println("  -h, --help              Show this help");
    }
}

