package com.j8d.karate.debug;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intuit.karate.Match;
import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
import com.intuit.karate.core.Variable;
import com.intuit.karate.graal.JsEngine;
import com.intuit.karate.graal.JsValue;

/**
 * Integrates with Karate's execution engine to provide debugging capabilities.
 * Uses Karate's RuntimeHook to intercept step execution for breakpoints and stepping.
 */
public class KarateDebugger implements RuntimeHook {
    private static final Logger logger = LoggerFactory.getLogger(KarateDebugger.class);
    private static final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

    /** Result of a setVariable operation */
    public record SetVariableResult(String displayValue, String type) {}

    /** Result of an evaluate operation */
    public record EvaluateResult(String value, String type) {}

    private final DapSession session;
    private final String workspaceRoot;
    private final String karateEnv;

    /** Breakpoint info including line and optional condition */
    public record BreakpointInfo(int line, String condition) {}

    // Breakpoint management: file path -> (line -> breakpoint info)
    private final Map<String, Map<Integer, BreakpointInfo>> breakpoints = new ConcurrentHashMap<>();

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

    // Pending variable changes to apply when resuming (thread-safe queue)
    // Key: variable name, Value: parsed value to set
    private final Map<String, Object> pendingVariableChanges = new ConcurrentHashMap<>();

    // Log breakpoints: patterns that trigger a pause when found in log output
    private volatile List<String> logBreakpointPatterns = List.of();
    private volatile boolean logBreakpointTriggered = false;
    private volatile String triggeredLogMessage = null;

    public KarateDebugger(DapSession session, String workspaceRoot, String karateEnv) {
        this.session = session;
        this.workspaceRoot = workspaceRoot;
        this.karateEnv = karateEnv;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Set log breakpoint patterns. When any of these patterns are found in log output,
     * execution will pause at the next step.
     */
    public void setLogBreakpoints(List<String> patterns) {
        this.logBreakpointPatterns = patterns != null ? patterns : List.of();
        if (!logBreakpointPatterns.isEmpty()) {
            logger.info("Log breakpoints set: {}", logBreakpointPatterns);
        }
    }

    /**
     * Called by the log appender to check if a log message should trigger a breakpoint.
     * If a pattern matches, sets a flag that will cause the next step to pause.
     */
    public void checkLogBreakpoint(String message) {
        if (logBreakpointPatterns.isEmpty() || message == null || !running || paused) {
            return;
        }
        String lowerMessage = message.toLowerCase();
        for (String pattern : logBreakpointPatterns) {
            if (lowerMessage.contains(pattern.toLowerCase())) {
                logBreakpointTriggered = true;
                triggeredLogMessage = message;
                return;
            }
        }
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
                logger.debug("Parsed feature path: {} at line {}", this.featurePath, this.featureLine);
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
        Map<Integer, BreakpointInfo> breakpointMap = new ConcurrentHashMap<>();
        JsonArray result = new JsonArray();

        for (int i = 0; i < breakpointsArray.size(); i++) {
            JsonObject bp = breakpointsArray.get(i).getAsJsonObject();
            int line = bp.get("line").getAsInt();
            String condition = bp.has("condition") ? bp.get("condition").getAsString() : null;

            breakpointMap.put(line, new BreakpointInfo(line, condition));

            JsonObject verified = new JsonObject();
            verified.addProperty("verified", true);
            verified.addProperty("line", line);
            result.add(verified);

            if (condition != null && !condition.isEmpty()) {
                logger.trace("Set conditional breakpoint at {}:{} with condition: {}", sourcePath, line, condition);
            } else {
                logger.trace("Set breakpoint at {}:{}", sourcePath, line);
            }
        }

        String normalizedPath = normalizeSourcePath(sourcePath);
        logger.trace("Storing breakpoint with key: {}", normalizedPath);
        breakpoints.put(normalizedPath, breakpointMap);
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
        logger.debug("startExecution called, featurePath={}, featureLine={}", featurePath, featureLine);

        if (featurePath == null) {
            logger.error("No feature path set");
            sendTerminatedEvent();
            return;
        }

        running = true;
        logger.debug("Starting execution thread");
        executionThread = new Thread(() -> {
            try {
                // Convert absolute path to classpath-relative path for Karate
                String classpathPath = toClasspathPath(featurePath);
                logger.debug("Classpath path: {}", classpathPath);

                // Build the path spec - Karate accepts classpath:path:lineNumber format
                String pathSpec = classpathPath;
                if (featureLine > 0) {
                    pathSpec = classpathPath + ":" + featureLine;
                }
                logger.debug("Starting Karate execution: {}", pathSpec);

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
            } catch (Throwable t) {
                logger.error("Unexpected error in execution thread", t);
                sendOutputEvent("console", "Unexpected error: " + t.getMessage());
            } finally {
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
        logger.trace("Before scenario: {}", sr.scenario.getName());
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

        logger.trace("beforeStep: {}:{}", sourcePath, line);

        boolean shouldPause = false;
        String pauseReason = "breakpoint";
        String matchedCondition = null;

        // Check for breakpoint (with optional condition)
        Map<Integer, BreakpointInfo> fileBreakpoints = breakpoints.get(sourcePath);
        if (fileBreakpoints != null) {
            BreakpointInfo bp = fileBreakpoints.get(line);
            if (bp != null) {
                // Check if there's a condition
                if (bp.condition() != null && !bp.condition().isEmpty()) {
                    // Evaluate the condition
                    if (evaluateBreakpointCondition(bp.condition())) {
                        logger.trace("Conditional breakpoint at {}:{} evaluated to true", sourcePath, line);
                        shouldPause = true;
                        matchedCondition = bp.condition();
                    } else {
                        logger.trace("Conditional breakpoint at {}:{} evaluated to false, skipping", sourcePath, line);
                    }
                } else {
                    // Unconditional breakpoint
                    logger.trace("Hit breakpoint at {}:{}", sourcePath, line);
                    shouldPause = true;
                }
            }
        }

        // Check step mode
        switch (stepMode) {
            case STEP_IN -> {
                shouldPause = true;
                pauseReason = "step";
            }
            case STEP_OVER -> {
                if (getCallDepth() <= stepDepth) {
                    shouldPause = true;
                    pauseReason = "step";
                }
            }
            case STEP_OUT -> {
                if (getCallDepth() < stepDepth) {
                    shouldPause = true;
                    pauseReason = "step";
                }
            }
            default -> { }
        }

        // Check for log breakpoint trigger
        if (logBreakpointTriggered) {
            shouldPause = true;
            pauseReason = "log breakpoint";
            matchedCondition = triggeredLogMessage;
            // Reset the trigger
            logBreakpointTriggered = false;
            triggeredLogMessage = null;
        }

        if (shouldPause && running) {
            pauseExecution(step, pauseReason, line, matchedCondition);
        }

        return true;
    }

    /**
     * Evaluates a breakpoint condition expression.
     * Returns true if the condition is met (breakpoint should trigger).
     */
    private boolean evaluateBreakpointCondition(String condition) {
        if (currentRuntime == null) {
            return true; // If no runtime, trigger the breakpoint
        }

        try {
            // Evaluate the condition as a Karate expression
            Variable result = currentRuntime.engine.evalKarateExpression(condition);

            if (result == null || result.isNull()) {
                return false;
            }

            Object value = result.getValue();

            // Handle boolean results
            if (value instanceof Boolean) {
                return (Boolean) value;
            }

            // Handle numeric results (non-zero = true)
            if (value instanceof Number) {
                return ((Number) value).doubleValue() != 0;
            }

            // Handle string results (non-empty = true)
            if (value instanceof String) {
                return !((String) value).isEmpty();
            }

            // Any other non-null value is truthy
            return true;

        } catch (Exception e) {
            logger.warn("Error evaluating breakpoint condition '{}': {}", condition, e.getMessage());
            // On error, trigger the breakpoint so user can investigate
            return true;
        }
    }

    @Override
    public void afterStep(StepResult result, ScenarioRuntime sr) {
        if (result.getResult().isFailed()) {
            sendOutputEvent("stderr", "Step failed: " + result.getStep().getText());
        }
    }

    private void pauseExecution(Step step, String reason, int line, String condition) {
        paused = true;
        pauseLatch = new CountDownLatch(1);

        // Log informative message about why we stopped
        if ("log breakpoint".equals(reason) && condition != null) {
            logger.info("Stopped: log breakpoint triggered by: {}", condition);
        } else if (condition != null && !condition.isEmpty()) {
            logger.info("Stopped: breakpoint at line {}, condition: {} is true", line, condition);
        } else if ("breakpoint".equals(reason)) {
            logger.info("Stopped: breakpoint at line {}", line);
        } else {
            logger.info("Stopped: {} at line {}", reason, line);
        }

        // Send stopped event to IDE
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

        // Apply any pending variable changes now that we're on the Karate execution thread
        applyPendingVariableChanges();
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
            // Get all variables from the JS engine bindings (includes magic variables)
            // Magic variables include parent scenario variables and config variables
            // that are not stored in engine.vars but are accessible in the JS context
            JsEngine jsEngine = currentRuntime.engine.getJsEngine();
            if (jsEngine != null) {
                Set<String> allKeys = jsEngine.bindings.getMemberKeys();

                for (String key : allKeys) {
                    // Skip internal/system variables that start with underscore or are functions
                    if (key.startsWith("_") || key.equals("karate")) {
                        continue;
                    }

                    try {
                        // Get value from JS bindings
                        Object value = jsEngine.bindings.getMember(key);

                        // Skip functions and other non-data values
                        if (value instanceof Value) {
                            Value graalValue = (Value) value;
                            if (graalValue.canExecute()) {
                                continue; // Skip functions
                            }
                            // Convert GraalVM Value to Java object
                            value = JsValue.toJava(graalValue);
                        }

                        JsonObject var = new JsonObject();
                        var.addProperty("name", key);
                        var.addProperty("value", formatValue(value));
                        var.addProperty("type", value != null ? value.getClass().getSimpleName() : "null");
                        var.addProperty("variablesReference", 0);
                        variables.add(var);
                    } catch (Exception e) {
                        // Skip variables that can't be accessed
                        logger.trace("Skipping variable '{}': {}", key, e.getMessage());
                    }
                }
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

    /**
     * Sets a variable value at runtime (hot reload).
     * Queues the change to be applied on the Karate execution thread when it resumes.
     * This is necessary because Karate's JS engine uses thread-local bindings.
     */
    public SetVariableResult setVariable(int variablesReference, String name, String value) {
        if (currentRuntime == null) {
            throw new IllegalStateException("No active runtime - cannot set variable");
        }

        Object parsedValue = parseValue(value);

        // Queue the change to be applied on the Karate execution thread.
        // We can't apply it here because the JS engine is thread-local.
        pendingVariableChanges.put(name, parsedValue);

        String displayValue = formatValue(parsedValue);
        String type = parsedValue != null ? parsedValue.getClass().getSimpleName() : "null";

        logger.debug("Queued variable change: {} = {}", name, displayValue);
        return new SetVariableResult(displayValue, type);
    }

    /**
     * Applies any pending variable changes on the current thread.
     * Must be called from the Karate execution thread.
     */
    private void applyPendingVariableChanges() {
        if (pendingVariableChanges.isEmpty() || currentRuntime == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : pendingVariableChanges.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            logger.debug("Applying variable change: {} = {}", name, formatValue(value));
            currentRuntime.engine.setVariable(name, value);
        }

        pendingVariableChanges.clear();
    }

    /**
     * Evaluates an expression in the Debug Console.
     * Supports:
     * - Variable inspection: response, response.name, myVar
     * - Match expressions: match response.name == 'pikachu'
     * - Karate expressions: response.types.length, karate.get('foo')
     */
    public EvaluateResult evaluate(String expression, String context) {
        if (currentRuntime == null) {
            return new EvaluateResult("No active runtime", "error");
        }

        // Only allow evaluation when truly paused to prevent interfering with step execution
        if (!paused) {
            return new EvaluateResult("Cannot evaluate while running", "error");
        }

        expression = expression.trim();
        logger.trace("Evaluating expression: '{}' (context: {})", expression, context);

        try {
            // Check if this is a match expression
            if (expression.startsWith("match ")) {
                return evaluateMatch(expression.substring(6).trim());
            }

            // For hover/watch context, check if the root variable exists first
            // to avoid corrupting engine state with evaluations of non-existent variables
            if ("hover".equals(context) || "watch".equals(context)) {
                String rootVar = expression.split("[.\\[\\(]")[0].trim();
                // Use hasVariable() to check JS bindings (includes magic variables)
                if (!currentRuntime.engine.hasVariable(rootVar)) {
                    return new EvaluateResult("undefined", "undefined");
                }
            }

            // Otherwise evaluate as a Karate expression
            Variable result = currentRuntime.engine.evalKarateExpression(expression);

            if (result == null || result.isNull()) {
                return new EvaluateResult("null", "null");
            }

            Object value = result.getValue();
            String displayValue = formatValue(value);
            String type = value != null ? value.getClass().getSimpleName() : "null";

            return new EvaluateResult(displayValue, type);

        } catch (Exception e) {
            logger.trace("Evaluation error: {}", e.getMessage());
            return new EvaluateResult("Error: " + e.getMessage(), "error");
        }
    }

    /**
     * Evaluates a match expression (e.g., "response.name == 'pikachu'").
     * Returns pass/fail result with details.
     */
    private EvaluateResult evaluateMatch(String matchExpression) {
        try {
            // Parse the match expression: "lhs == rhs" or "lhs contains rhs" etc.
            Match.Type matchType = Match.Type.EQUALS;
            String lhs;
            String rhs;

            // Find the match operator
            int opIndex = -1;
            String operator = null;

            // Check for different match operators (order matters - check longer ones first)
            String[] operators = {"!contains", "contains only", "contains deep", "contains any", "contains", "!=", "=="};
            for (String op : operators) {
                int idx = matchExpression.indexOf(" " + op + " ");
                if (idx != -1) {
                    opIndex = idx;
                    operator = op;
                    break;
                }
            }

            if (opIndex == -1 || operator == null) {
                return new EvaluateResult("Invalid match syntax. Use: match <expr> == <expected>", "error");
            }

            lhs = matchExpression.substring(0, opIndex).trim();
            rhs = matchExpression.substring(opIndex + operator.length() + 2).trim();

            // Check if the root variable on the left-hand side exists
            // to avoid corrupting engine state with evaluations of non-existent variables
            String rootVar = lhs.split("[.\\[\\(]")[0].trim();
            // Use hasVariable() to check JS bindings (includes magic variables)
            if (!currentRuntime.engine.hasVariable(rootVar)) {
                return new EvaluateResult("Variable not defined: " + rootVar, "error");
            }

            // Determine match type
            matchType = switch (operator) {
                case "==" -> Match.Type.EQUALS;
                case "!=" -> Match.Type.NOT_EQUALS;
                case "contains" -> Match.Type.CONTAINS;
                case "!contains" -> Match.Type.NOT_CONTAINS;
                case "contains only" -> Match.Type.CONTAINS_ONLY;
                case "contains any" -> Match.Type.CONTAINS_ANY;
                case "contains deep" -> Match.Type.CONTAINS_DEEP;
                default -> Match.Type.EQUALS;
            };

            // Use Karate's match method
            Match.Result matchResult = currentRuntime.engine.match(matchType, lhs, null, rhs);

            if (matchResult.pass) {
                return new EvaluateResult("PASS", "boolean");
            } else {
                return new EvaluateResult("FAIL: " + matchResult.message, "boolean");
            }

        } catch (Exception e) {
            logger.trace("Match evaluation error: {}", e.getMessage());
            return new EvaluateResult("Match error: " + e.getMessage(), "error");
        }
    }

    /**
     * Parses a string value into an appropriate Java object.
     * Supports: null, boolean, numbers, strings (single or double quoted), JSON objects/arrays.
     */
    private Object parseValue(String value) {
        if (value == null || value.equals("null")) return null;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;

        // Double-quoted string
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }

        // Single-quoted string (common in Karate feature files)
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }

        // JSON object or array
        if (value.startsWith("{") || value.startsWith("[")) {
            try {
                return com.intuit.karate.Json.of(value).value();
            } catch (Exception e) {
                logger.warn("Failed to parse JSON value: {}", value, e);
                return value; // Fall back to string
            }
        }

        // Try as number
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Not a number, treat as plain string
            return value;
        }
    }

    private void sendOutputEvent(String category, String output) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("output", formatOutputLine(output) + "\n");
        session.sendEvent("output", body);
    }

    /**
     * Formats an output line, pretty-printing JSON if detected.
     */
    private String formatOutputLine(String line) {
        String trimmed = line.trim();
        // Check if the line looks like it might be JSON (starts with { or [)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                var jsonElement = JsonParser.parseString(trimmed);
                return prettyGson.toJson(jsonElement);
            } catch (JsonSyntaxException e) {
                // Not valid JSON, return as-is
            }
        }
        return line;
    }

    private void sendTerminatedEvent() {
        session.sendEvent("terminated", null);
    }
}
