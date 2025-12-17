package com.j8d.karate.debug;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
import com.intuit.karate.core.Variable;

/**
 * Integrates with Karate's execution engine to provide debugging capabilities.
 * Uses Karate's RuntimeHook to intercept step execution for breakpoints and stepping.
 */
public class KarateDebugger implements RuntimeHook {
    private static final Logger logger = LoggerFactory.getLogger(KarateDebugger.class);

    private final DapSession session;
    private final String workspaceRoot;
    private final String karateEnv;

    // Breakpoint management
    private final Map<String, Set<Integer>> breakpoints = new ConcurrentHashMap<>();

    // Execution state
    private String featurePath;
    private int featureLine = -1;  // Line number for specific scenario
    private Thread executionThread;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private CountDownLatch pauseLatch;

    // Step control
    private enum StepMode { RUN, STEP_OVER, STEP_IN, STEP_OUT }
    private volatile StepMode stepMode = StepMode.RUN;
    private int stepDepth = 0;

    // Current execution context for variable inspection
    private ScenarioRuntime currentRuntime;
    private Step currentStep;
    private final AtomicInteger frameIdCounter = new AtomicInteger(1);
    private final AtomicInteger variableRefCounter = new AtomicInteger(1000);
    private final Map<Integer, Object> variableRefs = new ConcurrentHashMap<>();

    public KarateDebugger(DapSession session, String workspaceRoot, String karateEnv) {
        this.session = session;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setFeaturePath(String path) {
        // Parse line number if present (e.g., /path/to/feature.feature:23)
        if (path != null && path.contains(":")) {
            int colonIdx = path.lastIndexOf(':');
            String possibleLineNum = path.substring(colonIdx + 1);
            try {
                int lineNum = Integer.parseInt(possibleLineNum);
                String filePath = path.substring(0, colonIdx);

                // Check if this line is within a Scenario Outline (but not in Examples)
                // If so, run the whole feature instead
                if (isLineInScenarioOutline(filePath, lineNum)) {
                    logger.info("Line {} is in a Scenario Outline, running whole feature: {}", lineNum, filePath);
                    this.featurePath = filePath;
                    this.featureLine = -1;
                    return;
                }

                this.featureLine = lineNum;
                this.featurePath = filePath;
                logger.info("Parsed feature path: {} at line {}", this.featurePath, this.featureLine);
                return;
            } catch (NumberFormatException e) {
                // Not a line number, use full path
            }
        }
        this.featurePath = path;
        this.featureLine = -1;
    }

    /**
     * Check if the given line is within a Scenario Outline definition (before Examples).
     * Karate can't run a specific line within a Scenario Outline - it needs the Examples row.
     */
    private boolean isLineInScenarioOutline(String filePath, int lineNum) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(filePath).toPath());

            // Find if we're between "Scenario Outline:" and "Examples:" or end of outline
            boolean inScenarioOutline = false;
            int scenarioOutlineStart = -1;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                int currentLine = i + 1; // 1-based line numbers

                if (line.startsWith("Scenario Outline:")) {
                    inScenarioOutline = true;
                    scenarioOutlineStart = currentLine;
                } else if (inScenarioOutline && (line.startsWith("Examples:") || line.startsWith("Scenario:") || line.startsWith("Scenario Outline:"))) {
                    // End of the outline body
                    if (lineNum >= scenarioOutlineStart && lineNum < currentLine) {
                        return true;
                    }
                    inScenarioOutline = line.startsWith("Scenario Outline:");
                    scenarioOutlineStart = inScenarioOutline ? currentLine : -1;
                }
            }

            // If still in outline at end of file
            if (inScenarioOutline && lineNum >= scenarioOutlineStart) {
                return true;
            }

        } catch (Exception e) {
            logger.warn("Could not read feature file to check for Scenario Outline: {}", e.getMessage());
        }
        return false;
    }

    public JsonArray setBreakpoints(String sourcePath, JsonArray breakpointsArray) {
        Set<Integer> lines = new HashSet<>();
        JsonArray result = new JsonArray();

        for (int i = 0; i < breakpointsArray.size(); i++) {
            JsonObject bp = breakpointsArray.get(i).getAsJsonObject();
            int line = bp.get("line").getAsInt();
            lines.add(line);

            JsonObject verified = new JsonObject();
            verified.addProperty("verified", true);
            verified.addProperty("line", line);
            result.add(verified);

            logger.info("Set breakpoint at {}:{}", sourcePath, line);
        }

        String normalizedPath = normalizeSourcePath(sourcePath);
        logger.info("Storing breakpoint with key: {}", normalizedPath);
        breakpoints.put(normalizedPath, lines);
        return result;
    }

    private String normalizeSourcePath(String path) {
        File file = new File(path);
        if (file.isAbsolute()) {
            return file.getAbsolutePath();
        }
        // For relative paths from Karate, resolve against common source directories
        String[] sourceRoots = {
            "src/test/java/",
            "src/test/resources/",
            "src/main/java/",
            "src/main/resources/"
        };
        for (String root : sourceRoots) {
            File resolved = new File(workspaceRoot, root + path);
            if (resolved.exists()) {
                return resolved.getAbsolutePath();
            }
        }
        // Fallback: resolve against workspace root
        return new File(workspaceRoot, path).getAbsolutePath();
    }

    /**
     * Convert an absolute file path to a classpath-relative path for Karate.
     * e.g., /workspace/src/test/java/patient/auth.feature -> classpath:patient/auth.feature
     */
    private String toClasspathPath(String absolutePath) {
        // Common source directories to strip
        String[] sourceRoots = {
            "src/test/java/",
            "src/test/resources/",
            "src/main/java/",
            "src/main/resources/"
        };

        String normalizedPath = absolutePath.replace('\\', '/');

        for (String root : sourceRoots) {
            int idx = normalizedPath.indexOf(root);
            if (idx >= 0) {
                String relativePath = normalizedPath.substring(idx + root.length());
                return "classpath:" + relativePath;
            }
        }

        // If no source root found, try using the file directly
        // This handles cases where the file might be in a non-standard location
        logger.warn("Could not find source root in path: {}, using file path directly", absolutePath);
        return "file:" + absolutePath;
    }

    public void startExecution() {
        if (featurePath == null) {
            logger.error("No feature path set");
            sendTerminatedEvent();
            return;
        }

        running = true;
        executionThread = new Thread(() -> {
            // Capture stdout/stderr and redirect to Debug Console
            java.io.PrintStream originalOut = System.out;
            java.io.PrintStream originalErr = System.err;

            try {
                // Redirect stdout to Debug Console
                System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
                    private StringBuilder buffer = new StringBuilder();

                    @Override
                    public void write(int b) {
                        if (b == '\n') {
                            sendOutputEvent("stdout", buffer.toString());
                            buffer.setLength(0);
                        } else {
                            buffer.append((char) b);
                        }
                    }

                    @Override
                    public void flush() {
                        if (buffer.length() > 0) {
                            sendOutputEvent("stdout", buffer.toString());
                            buffer.setLength(0);
                        }
                    }
                }, true));

                // Redirect stderr to Debug Console
                System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
                    private StringBuilder buffer = new StringBuilder();

                    @Override
                    public void write(int b) {
                        if (b == '\n') {
                            sendOutputEvent("stderr", buffer.toString());
                            buffer.setLength(0);
                        } else {
                            buffer.append((char) b);
                        }
                    }

                    @Override
                    public void flush() {
                        if (buffer.length() > 0) {
                            sendOutputEvent("stderr", buffer.toString());
                            buffer.setLength(0);
                        }
                    }
                }, true));

                // Convert absolute path to classpath-relative path for Karate
                String classpathPath = toClasspathPath(featurePath);

                // Build the path spec - Karate accepts classpath:path:lineNumber format
                String pathSpec = classpathPath;
                if (featureLine > 0) {
                    pathSpec = classpathPath + ":" + featureLine;
                }
                logger.info("Starting Karate execution: {}", pathSpec);

                // Configure and run Karate
                Results results = Runner.path(pathSpec)
                    .hook(this)
                    .karateEnv(karateEnv)
                    .backupReportDir(false)
                    .parallel(1);

                logger.info("Karate execution completed. Passed: {}, Failed: {}",
                    results.getScenariosPassed(), results.getScenariosFailed());

            } catch (Exception e) {
                logger.error("Karate execution error", e);
                sendOutputEvent("console", "Error: " + e.getMessage());
            } finally {
                // Restore original streams
                System.setOut(originalOut);
                System.setErr(originalErr);

                running = false;
                sendTerminatedEvent();
            }
        }, "karate-execution");

        executionThread.start();
    }

    public void stop() {
        running = false;
        if (paused) {
            resumeExecution();
        }
        if (executionThread != null) {
            executionThread.interrupt();
        }
    }

    public void continueExecution() {
        stepMode = StepMode.RUN;
        resumeExecution();
    }

    public void stepOver() {
        stepMode = StepMode.STEP_OVER;
        stepDepth = getCallDepth();
        resumeExecution();
    }

    public void stepIn() {
        stepMode = StepMode.STEP_IN;
        resumeExecution();
    }

    public void stepOut() {
        stepMode = StepMode.STEP_OUT;
        stepDepth = getCallDepth();
        resumeExecution();
    }

    private void resumeExecution() {
        if (pauseLatch != null) {
            paused = false;
            pauseLatch.countDown();
        }
    }

    private int getCallDepth() {
        return currentRuntime != null ? 1 : 0; // Simplified depth tracking
    }

    // RuntimeHook implementation
    @Override
    public boolean beforeScenario(ScenarioRuntime sr) {
        currentRuntime = sr;
        logger.debug("Before scenario: {}", sr.scenario.getName());
        return true;
    }

    @Override
    public void afterScenario(ScenarioRuntime sr) {
        logger.debug("After scenario: {}", sr.scenario.getName());
    }

    @Override
    public boolean beforeStep(Step step, ScenarioRuntime sr) {
        currentStep = step;
        currentRuntime = sr;

        String relativePath = step.getFeature().getResource().getRelativePath();
        String sourcePath = normalizeSourcePath(relativePath);
        int line = step.getLine();

        logger.debug("beforeStep: relativePath={}, normalized={}, line={}", relativePath, sourcePath, line);
        logger.debug("beforeStep: breakpoints keys={}", breakpoints.keySet());

        boolean shouldPause = false;

        // Check for breakpoint
        Set<Integer> fileBreakpoints = breakpoints.get(sourcePath);
        if (fileBreakpoints != null && fileBreakpoints.contains(line)) {
            logger.info("Hit breakpoint at {}:{}", sourcePath, line);
            shouldPause = true;
        }

        // Check step mode
        switch (stepMode) {
            case STEP_IN -> shouldPause = true;
            case STEP_OVER -> {
                if (getCallDepth() <= stepDepth) {
                    shouldPause = true;
                }
            }
            case STEP_OUT -> {
                if (getCallDepth() < stepDepth) {
                    shouldPause = true;
                }
            }
            default -> { }
        }

        if (shouldPause && running) {
            pauseExecution(step, "breakpoint");
        }

        return true;
    }

    @Override
    public void afterStep(StepResult result, ScenarioRuntime sr) {
        if (result.getResult().isFailed()) {
            sendOutputEvent("stderr", "Step failed: " + result.getStep().getText());
        }
    }

    private void pauseExecution(Step step, String reason) {
        paused = true;
        pauseLatch = new CountDownLatch(1);

        // Send stopped event to VS Code
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason);
        body.addProperty("threadId", 1);
        body.addProperty("allThreadsStopped", true);
        session.sendEvent("stopped", body);

        // Wait until resumed
        try {
            pauseLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public JsonArray getStackFrames() {
        JsonArray frames = new JsonArray();

        if (currentStep != null && currentRuntime != null) {
            JsonObject frame = new JsonObject();
            frame.addProperty("id", frameIdCounter.getAndIncrement());
            frame.addProperty("name", currentStep.getText());
            frame.addProperty("line", currentStep.getLine());
            frame.addProperty("column", 1);

            // Use absolute path so VS Code opens the correct editor tab
            String relativePath = currentStep.getFeature().getResource().getRelativePath();
            String absolutePath = normalizeSourcePath(relativePath);

            JsonObject source = new JsonObject();
            source.addProperty("path", absolutePath);
            source.addProperty("name", currentStep.getFeature().getName());
            frame.add("source", source);

            frames.add(frame);
        }

        return frames;
    }

    public JsonArray getScopes(int frameId) {
        JsonArray scopes = new JsonArray();

        // Variables scope
        int varsRef = variableRefCounter.getAndIncrement();
        variableRefs.put(varsRef, "variables");

        JsonObject varsScope = new JsonObject();
        varsScope.addProperty("name", "Variables");
        varsScope.addProperty("variablesReference", varsRef);
        varsScope.addProperty("expensive", false);
        scopes.add(varsScope);

        return scopes;
    }

    public JsonArray getVariables(int variablesReference) {
        JsonArray variables = new JsonArray();

        if (currentRuntime != null) {
            Map<String, Variable> vars = currentRuntime.engine.vars;

            for (Map.Entry<String, Variable> entry : vars.entrySet()) {
                JsonObject var = new JsonObject();
                var.addProperty("name", entry.getKey());
                Object value = entry.getValue().getValue();
                var.addProperty("value", formatValue(value));
                var.addProperty("type", value != null ? value.getClass().getSimpleName() : "null");
                var.addProperty("variablesReference", 0);
                variables.add(var);
            }
        }

        return variables;
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Map || value instanceof List) {
            try {
                return new com.google.gson.Gson().toJson(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    private void sendOutputEvent(String category, String output) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("output", output + "\n");
        session.sendEvent("output", body);
    }

    private void sendTerminatedEvent() {
        session.sendEvent("terminated", null);
    }
}
