package com.j8d.karate.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for polyglot debugging initialization.
 * 
 * These tests verify that the polyglot debug session initializes correctly,
 * including the parent-child IPC communication and ready event handling.
 * 
 * This test class was created to catch issues like the sendMessage deadlock
 * where the synchronized lock on PolyglotDapSession blocked the IPC reader
 * thread from processing the ready event.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PolyglotInitializationTest {
    
    private static final String TEST_FIXTURES_PATH = findTestFixturesPath();
    private static final String FEATURE_FILE = "src/test/java/polyglot/polyglot-test.feature";
    
    // Timeout for initialization - if this is exceeded, there's likely a deadlock
    private static final int INIT_TIMEOUT_SECONDS = 15;
    
    private DebugServer server;
    private Thread serverThread;
    private DapTestClient client;
    private int port;

    private static String findTestFixturesPath() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path testFixtures = current.resolve("../../test-fixtures").normalize();
        if (Files.exists(testFixtures)) {
            return testFixtures.toAbsolutePath().toString();
        }
        testFixtures = current.resolve("test-fixtures");
        if (Files.exists(testFixtures)) {
            return testFixtures.toAbsolutePath().toString();
        }
        throw new IllegalStateException("Cannot find test-fixtures directory from " + current);
    }

    private static String getClasspath() throws Exception {
        Path classpathFile = Paths.get(TEST_FIXTURES_PATH, "target/debug-classpath.txt");
        if (!Files.exists(classpathFile)) {
            ProcessBuilder pb = new ProcessBuilder("mvn", "dependency:build-classpath",
                "-Dmdep.outputFile=target/debug-classpath.txt", "-q");
            pb.directory(new File(TEST_FIXTURES_PATH));
            pb.inheritIO();
            Process p = pb.start();
            if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                pb = new ProcessBuilder("mvn", "test-compile", "-q");
                pb.directory(new File(TEST_FIXTURES_PATH));
                pb.inheritIO();
                p = pb.start();
                p.waitFor(60, TimeUnit.SECONDS);
            }
        }

        String deps = Files.readString(classpathFile);
        String testClasses = Paths.get(TEST_FIXTURES_PATH, "target/test-classes").toString();
        Path jarPath = Paths.get(System.getProperty("user.dir"), "target/karate-debug-server-1.0.0.jar");
        if (!Files.exists(jarPath)) {
            ProcessBuilder pb = new ProcessBuilder("mvn", "package", "-DskipTests", "-q");
            pb.directory(new File(System.getProperty("user.dir")));
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor(120, TimeUnit.SECONDS);
        }

        return jarPath.toString() + File.pathSeparator + testClasses + File.pathSeparator + deps;
    }

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        
        String classpath = getClasspath();
        System.out.println("\n========================================");
        System.out.println("Starting debug server on port " + port);
        System.out.println("========================================\n");
        
        server = new DebugServer(port, TEST_FIXTURES_PATH, "dev", classpath, "debug", true, null);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "DebugServer");
        serverThread.start();
        
        Thread.sleep(500);
        client = new DapTestClient("localhost", port);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    /**
     * Tests that polyglot mode with Java debugging enabled initializes correctly.
     * 
     * This test catches deadlocks in the initialization flow, such as when
     * sendMessage blocks the IPC reader thread from processing the ready event.
     */
    @Test
    @Order(1)
    void testPolyglotInitializationWithJavaDebugging() throws Exception {
        System.out.println("\n=== TEST: Polyglot Initialization (Java) ===\n");
        
        // Initialize
        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean(), "Initialize should succeed");
        
        // Launch with Java debugging enabled
        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", true);
        launchArgs.addProperty("enableJsDebugging", false);
        
        // This is the critical test - launch should complete within timeout
        // If there's a deadlock, this will timeout
        long startTime = System.currentTimeMillis();
        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertTrue(launchResp.get("success").getAsBoolean(), 
            "Launch should succeed (took " + elapsed + "ms)");
        assertTrue(elapsed < INIT_TIMEOUT_SECONDS * 1000, 
            "Launch should complete within " + INIT_TIMEOUT_SECONDS + "s, took " + elapsed + "ms");
        System.out.println("Launch completed in " + elapsed + "ms");
        
        // Set a breakpoint
        String absFeaturePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toAbsolutePath().toString();
        JsonObject bpArgs = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("path", absFeaturePath);
        bpArgs.add("source", source);
        JsonArray breakpoints = new JsonArray();
        JsonObject bp = new JsonObject();
        bp.addProperty("line", 17);
        breakpoints.add(bp);
        bpArgs.add("breakpoints", breakpoints);
        JsonObject bpResp = client.sendRequest("setBreakpoints", bpArgs);
        assertTrue(bpResp.get("success").getAsBoolean(), "SetBreakpoints should succeed");
        
        // Configuration done
        JsonObject configResp = client.sendRequest("configurationDone", null);
        assertTrue(configResp.get("success").getAsBoolean(), "ConfigurationDone should succeed");
        
        // Wait for stopped event (breakpoint hit)
        JsonObject stoppedEvent = client.waitForEvent("stopped", 30000);
        assertNotNull(stoppedEvent, "Should receive stopped event for breakpoint");
        assertEquals("breakpoint", stoppedEvent.getAsJsonObject("body").get("reason").getAsString());
        
        System.out.println("SUCCESS: Polyglot initialization with Java debugging works!");
    }

    /**
     * Tests that polyglot mode with JS debugging enabled initializes correctly.
     */
    @Test
    @Order(2) 
    void testPolyglotInitializationWithJsDebugging() throws Exception {
        System.out.println("\n=== TEST: Polyglot Initialization (JS) ===\n");
        
        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean());
        
        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", false);
        launchArgs.addProperty("enableJsDebugging", true);
        
        long startTime = System.currentTimeMillis();
        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertTrue(launchResp.get("success").getAsBoolean(),
            "Launch should succeed (took " + elapsed + "ms)");
        assertTrue(elapsed < INIT_TIMEOUT_SECONDS * 1000,
            "Launch should complete within " + INIT_TIMEOUT_SECONDS + "s");
        
        System.out.println("SUCCESS: Polyglot initialization with JS debugging works!");
    }

    /**
     * Tests that polyglot mode with both Java and JS debugging initializes correctly.
     */
    @Test
    @Order(3)
    void testPolyglotInitializationWithBothLanguages() throws Exception {
        System.out.println("\n=== TEST: Polyglot Initialization (Java + JS) ===\n");

        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean());

        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", true);
        launchArgs.addProperty("enableJsDebugging", true);

        long startTime = System.currentTimeMillis();
        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(launchResp.get("success").getAsBoolean(),
            "Launch should succeed (took " + elapsed + "ms)");
        assertTrue(elapsed < INIT_TIMEOUT_SECONDS * 1000,
            "Launch should complete within " + INIT_TIMEOUT_SECONDS + "s");

        System.out.println("SUCCESS: Polyglot initialization with both languages works!");
    }

    /**
     * Tests that breakpoints set before child process is ready get verified
     * when the backends become ready.
     *
     * This test verifies that:
     * 1. Breakpoints set during CHILD_STARTING state are queued
     * 2. Once backends are ready, applyQueuedBreakpoints is called
     * 3. A breakpoint event with verified=true is sent to the client
     */
    @Test
    @Order(4)
    void testBreakpointVerificationWithQueuedBreakpoints() throws Exception {
        System.out.println("\n=== TEST: Breakpoint Verification (Queued) ===\n");

        // Initialize
        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean(), "Initialize should succeed");

        // Wait for initialized event - this is when VS Code sends setBreakpoints
        JsonObject initializedEvent = client.waitForEvent("initialized", 5000);
        assertNotNull(initializedEvent, "Should receive initialized event");
        System.out.println("Received initialized event");

        // Send setBreakpoints BEFORE launch (like VS Code does)
        // At this point child process hasn't started, so breakpoints will be queued
        String absFeaturePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toAbsolutePath().toString();
        JsonObject bpArgs = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("path", absFeaturePath);
        bpArgs.add("source", source);
        JsonArray breakpoints = new JsonArray();
        JsonObject bp = new JsonObject();
        bp.addProperty("line", 17);
        breakpoints.add(bp);
        bpArgs.add("breakpoints", breakpoints);

        JsonObject bpResp = client.sendRequest("setBreakpoints", bpArgs);
        assertTrue(bpResp.get("success").getAsBoolean(), "SetBreakpoints should succeed");

        // Initial response should have verified=false (pending) since child isn't started yet
        JsonArray bpArray = bpResp.getAsJsonObject("body").getAsJsonArray("breakpoints");
        boolean initiallyVerified = bpArray.get(0).getAsJsonObject().get("verified").getAsBoolean();
        System.out.println("Initial breakpoint verified=" + initiallyVerified);
        // Note: It might be verified immediately if the state is already BACKENDS_READY
        // The key test is that we receive a breakpoint event later

        // Now send launch
        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", true);
        launchArgs.addProperty("enableJsDebugging", false);

        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        assertTrue(launchResp.get("success").getAsBoolean(), "Launch should succeed");
        System.out.println("Launch completed");

        // Send configurationDone
        JsonObject configResp = client.sendRequest("configurationDone", null);
        assertTrue(configResp.get("success").getAsBoolean(), "ConfigurationDone should succeed");
        System.out.println("ConfigurationDone completed");

        // If breakpoint was initially unverified, wait for the breakpoint event
        if (!initiallyVerified) {
            JsonObject bpEvent = client.waitForEvent("breakpoint", 10000);
            assertNotNull(bpEvent, "Should receive breakpoint event with verified=true");
            JsonObject bpBody = bpEvent.getAsJsonObject("body");
            assertEquals("changed", bpBody.get("reason").getAsString(), "Reason should be 'changed'");
            assertTrue(bpBody.getAsJsonObject("breakpoint").get("verified").getAsBoolean(),
                "Breakpoint should now be verified");
            System.out.println("Received breakpoint event with verified=true!");
        } else {
            System.out.println("Breakpoint was already verified (state was BACKENDS_READY)");
        }

        // Wait for stopped event (breakpoint hit)
        JsonObject stoppedEvent = client.waitForEvent("stopped", 30000);
        assertNotNull(stoppedEvent, "Should receive stopped event for breakpoint");
        assertEquals("breakpoint", stoppedEvent.getAsJsonObject("body").get("reason").getAsString());
        System.out.println("Breakpoint hit!");

        System.out.println("SUCCESS: Queued breakpoints are properly verified!");
    }
}

