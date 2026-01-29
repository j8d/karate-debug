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
 * Integration test for cross-language step-into functionality.
 * Tests stepping from Karate into Java code.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrossLanguageStepIntoTest {
    
    private static final String TEST_FIXTURES_PATH = findTestFixturesPath();
    private static final String FEATURE_FILE = "src/test/java/polyglot/polyglot-test.feature";
    private static final String JAVA_FILE = "src/test/java/polyglot/PolyglotHelper.java";
    
    private DebugServer server;
    private Thread serverThread;
    private DapTestClient client;
    private int port;

    private static String findTestFixturesPath() {
        // Navigate from shared/debug-server to test-fixtures
        Path current = Paths.get(System.getProperty("user.dir"));
        Path testFixtures = current.resolve("../../test-fixtures").normalize();
        if (Files.exists(testFixtures)) {
            return testFixtures.toAbsolutePath().toString();
        }
        // Fallback for running from project root
        testFixtures = current.resolve("test-fixtures");
        if (Files.exists(testFixtures)) {
            return testFixtures.toAbsolutePath().toString();
        }
        throw new IllegalStateException("Cannot find test-fixtures directory from " + current);
    }

    private static String getClasspath() throws Exception {
        Path classpathFile = Paths.get(TEST_FIXTURES_PATH, "target/debug-classpath.txt");
        if (!Files.exists(classpathFile)) {
            // Generate classpath
            System.out.println("Generating classpath for test-fixtures...");
            ProcessBuilder pb = new ProcessBuilder("mvn", "dependency:build-classpath",
                "-Dmdep.outputFile=target/debug-classpath.txt", "-q");
            pb.directory(new File(TEST_FIXTURES_PATH));
            pb.inheritIO();
            Process p = pb.start();
            if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                // Also compile
                pb = new ProcessBuilder("mvn", "test-compile", "-q");
                pb.directory(new File(TEST_FIXTURES_PATH));
                pb.inheritIO();
                p = pb.start();
                p.waitFor(60, TimeUnit.SECONDS);
            }
        }

        String deps = Files.readString(classpathFile);
        String testClasses = Paths.get(TEST_FIXTURES_PATH, "target/test-classes").toString();

        // Use the shaded JAR which includes Gson and our classes
        Path jarPath = Paths.get(System.getProperty("user.dir"), "target/karate-debug-server-1.0.0.jar");
        if (!Files.exists(jarPath)) {
            // Build the JAR if it doesn't exist
            System.out.println("Building debug server JAR...");
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
        // Find an available port
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        
        String classpath = getClasspath();
        System.out.println("\n========================================");
        System.out.println("Starting debug server on port " + port);
        System.out.println("Workspace: " + TEST_FIXTURES_PATH);
        System.out.println("========================================\n");
        
        // Start server in background thread
        server = new DebugServer(port, TEST_FIXTURES_PATH, "dev", classpath, true);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "DebugServer");
        serverThread.start();
        
        // Give server time to start
        Thread.sleep(500);
        
        // Connect client
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

    @Test
    @Order(1)
    void testCrossLanguageStepIntoJava() throws Exception {
        System.out.println("\n=== TEST: Cross-Language Step Into Java ===\n");
        
        // 1. Initialize
        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean(), "Initialize should succeed");
        System.out.println("Initialized successfully");
        
        // 2. Launch with polyglot options enabled
        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", true);  // Enable Java debugging
        launchArgs.addProperty("enableJsDebugging", false);   // Disable JS debugging for this test
        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        assertTrue(launchResp.get("success").getAsBoolean(), "Launch should succeed");
        System.out.println("Launched: " + featurePath);
        
        // 3. Set breakpoint on line 16 (before Java call)
        String absFeaturePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toAbsolutePath().toString();
        JsonObject bpArgs = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("path", absFeaturePath);
        bpArgs.add("source", source);
        JsonArray breakpoints = new JsonArray();
        JsonObject bp = new JsonObject();
        bp.addProperty("line", 16);
        breakpoints.add(bp);
        bpArgs.add("breakpoints", breakpoints);
        JsonObject bpResp = client.sendRequest("setBreakpoints", bpArgs);
        assertTrue(bpResp.get("success").getAsBoolean(), "SetBreakpoints should succeed");
        System.out.println("Set breakpoint at line 16");
        
        // 4. Configuration done - this starts execution
        JsonObject configResp = client.sendRequest("configurationDone", null);
        assertTrue(configResp.get("success").getAsBoolean(), "ConfigurationDone should succeed");
        System.out.println("Configuration done, waiting for breakpoint...");

        // 5. Wait for stopped event (breakpoint hit)
        JsonObject stoppedEvent = client.waitForEvent("stopped", 30000);
        assertNotNull(stoppedEvent, "Should receive stopped event for breakpoint");
        JsonObject body = stoppedEvent.getAsJsonObject("body");
        assertEquals("breakpoint", body.get("reason").getAsString(), "Stop reason should be breakpoint");
        int karateThreadId = body.get("threadId").getAsInt();
        System.out.println("Stopped at breakpoint, threadId=" + karateThreadId);

        // 6. Get stack frames to verify we're at line 16
        JsonObject sfArgs = new JsonObject();
        sfArgs.addProperty("threadId", karateThreadId);
        JsonObject sfResp = client.sendRequest("stackTrace", sfArgs);
        assertTrue(sfResp.get("success").getAsBoolean(), "StackTrace should succeed");
        JsonArray frames = sfResp.getAsJsonObject("body").getAsJsonArray("stackFrames");
        assertTrue(frames.size() > 0, "Should have at least one stack frame");
        JsonObject topFrame = frames.get(0).getAsJsonObject();
        assertEquals(16, topFrame.get("line").getAsInt(), "Should be at line 16");
        System.out.println("Verified at line 16: " + topFrame.get("name").getAsString());

        // 7. Step into - this should step into the Java code
        JsonObject stepArgs = new JsonObject();
        stepArgs.addProperty("threadId", karateThreadId);
        JsonObject stepResp = client.sendRequest("stepIn", stepArgs);
        assertTrue(stepResp.get("success").getAsBoolean(), "StepIn should succeed");
        System.out.println("Step into requested, waiting for Java stopped event...");

        // 8. Wait for stopped event from Java backend
        JsonObject javaStoppedEvent = client.waitForEvent("stopped", 30000);
        assertNotNull(javaStoppedEvent, "Should receive stopped event from Java");
        JsonObject javaBody = javaStoppedEvent.getAsJsonObject("body");
        int javaThreadId = javaBody.get("threadId").getAsInt();
        System.out.println("Java stopped, threadId=" + javaThreadId + ", reason=" + javaBody.get("reason"));

        // Java thread IDs are in range 2000-2999
        assertTrue(javaThreadId >= 2000 && javaThreadId < 3000,
            "Thread ID " + javaThreadId + " should be in Java range (2000-2999)");

        // 9. Get stack frames from Java thread
        JsonObject javaSfArgs = new JsonObject();
        javaSfArgs.addProperty("threadId", javaThreadId);
        JsonObject javaSfResp = client.sendRequest("stackTrace", javaSfArgs);
        assertTrue(javaSfResp.get("success").getAsBoolean(), "Java StackTrace should succeed");
        JsonArray javaFrames = javaSfResp.getAsJsonObject("body").getAsJsonArray("stackFrames");
        assertTrue(javaFrames.size() > 0, "Should have Java stack frames");

        // 10. Verify top frame is PolyglotHelper.validateOrder at line 18
        JsonObject javaTopFrame = javaFrames.get(0).getAsJsonObject();
        String frameName = javaTopFrame.get("name").getAsString();
        int line = javaTopFrame.get("line").getAsInt();
        JsonObject javaSource = javaTopFrame.getAsJsonObject("source");
        String sourcePath = javaSource.get("path").getAsString();

        System.out.println("\n========================================");
        System.out.println("RESULT: Cross-language step-into");
        System.out.println("  Frame: " + frameName);
        System.out.println("  Source: " + sourcePath);
        System.out.println("  Line: " + line);
        System.out.println("========================================\n");

        assertTrue(frameName.contains("validateOrder"),
            "Frame should be validateOrder, got: " + frameName);
        assertTrue(sourcePath.contains("PolyglotHelper.java"),
            "Source should be PolyglotHelper.java, got: " + sourcePath);
        assertEquals(18, line, "Should be at line 18 in PolyglotHelper.java");

        System.out.println("SUCCESS: Cross-language step-into works correctly!");
    }
}

