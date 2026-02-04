package com.j8d.karate.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for cross-language step-into functionality.
 * Tests stepping from Karate into JavaScript code.
 *
 * DISABLED: GraalVM version mismatch between Karate (polyglot/truffle 24.0.0) and
 * dap-tool (25.0.2) causes NoSuchMethodError when starting the DAP server.
 * The dap-tool calls SuspensionFilter$Builder.sourceSectionAvailableOnly() which
 * doesn't exist in truffle-api 24.0.0.
 */
@Disabled("GraalVM version mismatch between Karate (24.0.0) and dap-tool (25.0.2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CrossLanguageStepIntoJsTest {
    
    private static final String TEST_FIXTURES_PATH = findTestFixturesPath();
    private static final String FEATURE_FILE = "src/test/java/polyglot/polyglot-test.feature";
    
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
            System.out.println("Generating classpath for test-fixtures...");
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
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        
        String classpath = getClasspath();
        System.out.println("\n========================================");
        System.out.println("Starting debug server on port " + port);
        System.out.println("Workspace: " + TEST_FIXTURES_PATH);
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

    @Test
    @Order(1)
    void testCrossLanguageStepIntoJavaScript() throws Exception {
        System.out.println("\n=== TEST: Cross-Language Step Into JavaScript ===\n");
        
        // 1. Initialize
        JsonObject initArgs = new JsonObject();
        initArgs.addProperty("clientID", "test");
        initArgs.addProperty("adapterID", "karate");
        JsonObject initResp = client.sendRequest("initialize", initArgs);
        assertTrue(initResp.get("success").getAsBoolean(), "Initialize should succeed");
        System.out.println("Initialized successfully");
        
        // 2. Launch with JavaScript debugging enabled
        String featurePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toString();
        JsonObject launchArgs = new JsonObject();
        launchArgs.addProperty("feature", featurePath);
        launchArgs.addProperty("env", "dev");
        launchArgs.addProperty("enableJavaDebugging", false);
        launchArgs.addProperty("enableJsDebugging", true);  // Enable JS debugging
        JsonObject launchResp = client.sendRequest("launch", launchArgs);
        assertTrue(launchResp.get("success").getAsBoolean(), "Launch should succeed");
        System.out.println("Launched with JS debugging: " + featurePath);
        
        // 3. Set breakpoint on line 25 (the JS call: jsHelper.processOrder(order))
        String absFeaturePath = Paths.get(TEST_FIXTURES_PATH, FEATURE_FILE).toAbsolutePath().toString();
        JsonObject bpArgs = new JsonObject();
        JsonObject source = new JsonObject();
        source.addProperty("path", absFeaturePath);
        bpArgs.add("source", source);
        JsonArray breakpoints = new JsonArray();
        JsonObject bp = new JsonObject();
        bp.addProperty("line", 25);
        breakpoints.add(bp);
        bpArgs.add("breakpoints", breakpoints);
        JsonObject bpResp = client.sendRequest("setBreakpoints", bpArgs);
        assertTrue(bpResp.get("success").getAsBoolean(), "SetBreakpoints should succeed");
        System.out.println("Set breakpoint at line 25");

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

        // 6. Get stack frames to verify we're at line 25 (the JS call)
        JsonObject sfArgs = new JsonObject();
        sfArgs.addProperty("threadId", karateThreadId);
        JsonObject sfResp = client.sendRequest("stackTrace", sfArgs);
        assertTrue(sfResp.get("success").getAsBoolean(), "StackTrace should succeed");
        JsonArray frames = sfResp.getAsJsonObject("body").getAsJsonArray("stackFrames");
        assertTrue(frames.size() > 0, "Should have at least one stack frame");
        JsonObject topFrame = frames.get(0).getAsJsonObject();
        assertEquals(25, topFrame.get("line").getAsInt(), "Should be at line 25");
        System.out.println("Verified at line 25: " + topFrame.get("name").getAsString());

        // 7. Step into - this should step into the JavaScript code
        JsonObject stepArgs = new JsonObject();
        stepArgs.addProperty("threadId", karateThreadId);
        JsonObject stepResp = client.sendRequest("stepIn", stepArgs);
        assertTrue(stepResp.get("success").getAsBoolean(), "StepIn should succeed");
        System.out.println("Step into requested, waiting for JavaScript stopped event...");

        // 8. Wait for stopped event from JavaScript backend
        JsonObject jsStoppedEvent = client.waitForEvent("stopped", 30000);
        assertNotNull(jsStoppedEvent, "Should receive stopped event from JavaScript");
        JsonObject jsBody = jsStoppedEvent.getAsJsonObject("body");
        int jsThreadId = jsBody.get("threadId").getAsInt();
        System.out.println("JavaScript stopped, threadId=" + jsThreadId + ", reason=" + jsBody.get("reason"));

        // JavaScript thread IDs are in range 1000-1999 (per IdRange.JAVASCRIPT_THREADS)
        assertTrue(jsThreadId >= 1000 && jsThreadId < 2000,
            "Thread ID " + jsThreadId + " should be in JavaScript range (1000-1999)");

        // 9. Get stack frames from JavaScript thread
        JsonObject jsSfArgs = new JsonObject();
        jsSfArgs.addProperty("threadId", jsThreadId);
        JsonObject jsSfResp = client.sendRequest("stackTrace", jsSfArgs);
        assertTrue(jsSfResp.get("success").getAsBoolean(), "JavaScript StackTrace should succeed");
        JsonArray jsFrames = jsSfResp.getAsJsonObject("body").getAsJsonArray("stackFrames");
        assertTrue(jsFrames.size() > 0, "Should have JavaScript stack frames");

        // 10. Verify top frame is processOrder function
        JsonObject jsTopFrame = jsFrames.get(0).getAsJsonObject();
        String frameName = jsTopFrame.get("name").getAsString();
        int line = jsTopFrame.get("line").getAsInt();
        JsonObject jsSource = jsTopFrame.getAsJsonObject("source");
        String sourcePath = jsSource.has("path") ? jsSource.get("path").getAsString() : "unknown";

        System.out.println("\n========================================");
        System.out.println("RESULT: Cross-language step-into JavaScript");
        System.out.println("  Frame: " + frameName);
        System.out.println("  Source: " + sourcePath);
        System.out.println("  Line: " + line);
        System.out.println("========================================\n");

        // The frame should be in processOrder function (line 25-38 in polyglot-helper.js)
        assertTrue(frameName.contains("processOrder") || line >= 25,
            "Frame should be processOrder or at line >= 25, got: " + frameName + " at line " + line);

        System.out.println("SUCCESS: Cross-language step-into JavaScript works correctly!");
    }
}

