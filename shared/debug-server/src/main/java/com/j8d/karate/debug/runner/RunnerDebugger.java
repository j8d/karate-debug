package com.j8d.karate.debug.runner;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intuit.karate.Match;
import com.intuit.karate.Results;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.FeatureRuntime;
import com.intuit.karate.core.ScenarioRuntime;
import com.intuit.karate.core.Step;
import com.intuit.karate.core.StepResult;
import com.intuit.karate.core.Variable;
import com.j8d.karate.debug.ipc.IpcEvents;
import com.j8d.karate.debug.ipc.IpcServer;

/**
 * Debugger implementation for the child runner process.
 *
 * This class handles the actual Karate debugging operations and sends
 * events back to the parent via IPC. It implements RuntimeHook to intercept
 * Karate step execution for breakpoints and stepping.
 */
public class RunnerDebugger implements RuntimeHook {

    private static final Logger log = LoggerFactory.getLogger(RunnerDebugger.class);

    private final KarateRunner runner;
    private final IpcServer ipcServer;
    private final String workspaceRoot;

    /** Breakpoint info including line and optional condition */
    public record BreakpointInfo(int line, String condition) {}

    // Breakpoint management: file path -> (line -> breakpoint info)
    private final Map<String, Map<Integer, BreakpointInfo>> breakpoints = new ConcurrentHashMap<>();

    // Execution state
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
    private final Map<String, Object> pendingVariableChanges = new ConcurrentHashMap<>();

    public RunnerDebugger(KarateRunner runner) {
        this.runner = runner;
        this.ipcServer = runner.getIpcServer();
        this.workspaceRoot = runner.getWorkspaceRoot();
    }

    // ========== Lifecycle ==========

    /**
     * Starts Karate execution with debugging enabled.
     */
    public void start() {
        String featurePath = runner.getFeaturePath();
        int featureLine = runner.getFeatureLine();
        String karateEnv = runner.getKarateEnv();

        log.debug("Starting execution, featurePath={}, featureLine={}", featurePath, featureLine);

        if (featurePath == null) {
            log.error("No feature path set");
            sendTerminated();
            return;
        }

        running = true;
        executionThread = new Thread(() -> {
            try {
                String classpathPath = toClasspathPath(featurePath);
                log.trace("Classpath path: {}", classpathPath);

                String pathSpec = classpathPath;
                if (featureLine > 0) {
                    pathSpec = classpathPath + ":" + featureLine;
                }
                log.debug("Starting Karate execution: {}", pathSpec);
                log.trace("Hook instance: {}, breakpoints: {}", System.identityHashCode(this), breakpoints.keySet());

                Results results = Runner.path(pathSpec)
                    .hook(this)
                    .karateEnv(karateEnv)
                    .backupReportDir(false)
                    .parallel(1);

                log.info("Karate execution completed. Passed: {}, Failed: {}",
                    results.getScenariosPassed(), results.getScenariosFailed());

            } catch (Exception e) {
                log.error("Karate execution error", e);
                sendOutput("console", "Error: " + e.getMessage());
            } finally {
                running = false;
                sendTerminated();
            }
        }, "karate-execution");

        executionThread.start();
    }

    /**
     * Stops Karate execution.
     */
    public void stop() {
        log.info("Stopping Karate debugger");
        running = false;
        if (paused) {
            resumeExecution();
        }
        if (executionThread != null) {
            executionThread.interrupt();
        }
    }

    // ========== Breakpoints ==========

    /**
     * Sets breakpoints in a file.
     */
    public JsonObject setBreakpoints(String filePath, JsonArray breakpointsArray) {
        log.trace("setBreakpoints called on instance: {}", System.identityHashCode(this));
        Map<Integer, BreakpointInfo> breakpointMap = new ConcurrentHashMap<>();
        JsonArray result = new JsonArray();

        for (int i = 0; i < breakpointsArray.size(); i++) {
            JsonObject bp = breakpointsArray.get(i).getAsJsonObject();
            int line = bp.get("line").getAsInt();
            String condition = bp.has("condition") ? bp.get("condition").getAsString() : null;

            breakpointMap.put(line, new BreakpointInfo(line, condition));

            JsonObject verified = new JsonObject();
            verified.addProperty("id", i + 1);
            verified.addProperty("verified", true);
            verified.addProperty("line", line);
            verified.addProperty("source", filePath);
            result.add(verified);

            if (condition != null && !condition.isEmpty()) {
                log.trace("Set conditional breakpoint at {}:{} with condition: {}", filePath, line, condition);
            } else {
                log.trace("Set breakpoint at {}:{}", filePath, line);
            }
        }

        String normalizedPath = normalizeSourcePath(filePath);
        log.trace("Storing breakpoint with key: {} (original: {})", normalizedPath, filePath);
        breakpoints.put(normalizedPath, breakpointMap);
        log.trace("Breakpoints map now has keys: {}", breakpoints.keySet());

        JsonObject response = new JsonObject();
        response.add("breakpoints", result);
        return response;
    }

    // ========== Execution Control ==========

    public void resume(int threadId) {
        log.debug("Resume thread {}", threadId);
        stepMode = StepMode.RUN;
        resumeExecution();
    }

    public void stepOver(int threadId) {
        stepMode = StepMode.STEP_OVER;
        stepDepth = getCallDepth();
        resumeExecution();
    }

    public void stepInto(int threadId) {
        stepMode = StepMode.STEP_IN;
        resumeExecution();
    }

    public void stepOut(int threadId) {
        stepMode = StepMode.STEP_OUT;
        stepDepth = getCallDepth();
        resumeExecution();
    }

    public void pause(int threadId) {
        log.debug("Pause thread {}", threadId);
        // Set step mode to step-in so we pause at the next step
        stepMode = StepMode.STEP_IN;
    }

    private void resumeExecution() {
        if (pauseLatch != null) {
            paused = false;
            pauseLatch.countDown();
        }
    }

    private int getCallDepth() {
        return currentRuntime != null ? 1 : 0;
    }

    // ========== RuntimeHook Implementation ==========

    @Override
    public boolean beforeFeature(FeatureRuntime fr) {
        log.trace("beforeFeature: {} (suite.dryRun={}, suite.hooks.size={}, suite.isAborted={})",
            fr.featureCall.feature.getResource().getRelativePath(),
            fr.suite.dryRun,
            fr.suite.hooks.size(),
            fr.suite.isAborted());
        // Log hook instances to verify this hook is in the list
        int idx = 0;
        for (com.intuit.karate.RuntimeHook h : fr.suite.hooks) {
            log.trace("beforeFeature: suite.hooks[{}] = {} (isThis={})",
                idx++, System.identityHashCode(h), h == this);
        }
        return true;
    }

    @Override
    public void afterFeature(FeatureRuntime fr) {
        log.debug("afterFeature: {}", fr.featureCall.feature.getResource().getRelativePath());
    }

    @Override
    public boolean beforeScenario(ScenarioRuntime sr) {
        currentRuntime = sr;
        log.debug("beforeScenario CALLED: {} (dryRun={}) - returning true",
            sr.scenario.getName(), sr.dryRun);
        return true;
    }

    @Override
    public void afterScenario(ScenarioRuntime sr) {
        // Log diagnostic info to understand why beforeScenario might have been skipped
        log.debug("afterScenario CALLED: {} (dryRun={}, stopped={}, engineAborted={})",
            sr.scenario.getName(), sr.dryRun, sr.isStopped(),
            sr.engine != null ? sr.engine.isAborted() : "null-engine");
        // Check scenario properties that could cause beforeScenario to be skipped
        log.debug("afterScenario: scenario.isDynamic={}, scenario.isOutlineExample={}",
            sr.scenario.isDynamic(), sr.scenario.isOutlineExample());
        // Check if suite was aborted
        try {
            log.debug("afterScenario: suite.isAborted={}", sr.featureRuntime.suite.isAborted());
        } catch (Exception e) {
            log.debug("afterScenario: could not check suite.isAborted: {}", e.getMessage());
        }
        // Check step results to understand execution
        try {
            log.debug("afterScenario: stepResults.size={}, scenario.getSteps().size={}",
                sr.result.getStepResults().size(),
                sr.scenario.getSteps().size());
            // Log first step result if any
            if (!sr.result.getStepResults().isEmpty()) {
                var firstStep = sr.result.getStepResults().get(0);
                log.debug("afterScenario: firstStepResult={}, isFailed={}",
                    firstStep.getStep() != null ? firstStep.getStep().getText() : "null-step",
                    firstStep.isFailed());
            }
        } catch (Exception e) {
            log.debug("afterScenario: could not check steps: {}", e.getMessage());
        }
    }

    @Override
    public boolean beforeStep(Step step, ScenarioRuntime sr) {
        currentStep = step;
        currentRuntime = sr;

        String relativePath = step.getFeature().getResource().getRelativePath();
        String sourcePath = normalizeSourcePath(relativePath);
        int line = step.getLine();

        log.trace("beforeStep: line={}, relativePath={}, normalizedPath={}", line, relativePath, sourcePath);
        log.trace("beforeStep: breakpoints keys={}", breakpoints.keySet());

        boolean shouldPause = false;
        String pauseReason = "breakpoint";

        // Check for breakpoint (with optional condition)
        Map<Integer, BreakpointInfo> fileBreakpoints = breakpoints.get(sourcePath);
        log.trace("beforeStep: fileBreakpoints for path={} -> {}", sourcePath, fileBreakpoints);
        if (fileBreakpoints != null) {
            BreakpointInfo bp = fileBreakpoints.get(line);
            if (bp != null) {
                if (bp.condition() != null && !bp.condition().isEmpty()) {
                    if (evaluateBreakpointCondition(bp.condition())) {
                        log.trace("Conditional breakpoint at {}:{} evaluated to true", sourcePath, line);
                        shouldPause = true;
                    } else {
                        log.trace("Conditional breakpoint at {}:{} evaluated to false, skipping", sourcePath, line);
                    }
                } else {
                    log.trace("Hit breakpoint at {}:{}", sourcePath, line);
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

        if (shouldPause && running) {
            pauseExecution(step, pauseReason, line);
        }

        return true;
    }

    @Override
    public void afterStep(StepResult result, ScenarioRuntime sr) {
        if (result.getResult().isFailed()) {
            sendOutput("stderr", "Step failed: " + result.getStep().getText());
        }
    }

    private boolean evaluateBreakpointCondition(String condition) {
        if (currentRuntime == null) {
            return true;
        }

        try {
            Variable result = currentRuntime.engine.evalKarateExpression(condition);

            if (result == null || result.isNull()) {
                return false;
            }

            Object value = result.getValue();

            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number) {
                return ((Number) value).doubleValue() != 0;
            }
            if (value instanceof String) {
                return !((String) value).isEmpty();
            }
            return true;

        } catch (Exception e) {
            log.warn("Error evaluating breakpoint condition '{}': {}", condition, e.getMessage());
            return true;
        }
    }

    private void pauseExecution(Step step, String reason, int line) {
        paused = true;
        pauseLatch = new CountDownLatch(1);

        // Build description with file:line info for user-friendly logging
        // Always use filename (not feature title) for clearer output
        String description = null;
        if (step != null && step.getFeature() != null) {
            String relativePath = step.getFeature().getResource().getRelativePath();
            if (relativePath != null) {
                int lastSlash = relativePath.lastIndexOf('/');
                String fileName = lastSlash >= 0 ? relativePath.substring(lastSlash + 1) : relativePath;
                description = fileName + ":" + line;
            }
        }

        // Send stopped event via IPC with file:line description
        // Note: User-facing log is in DapMultiplexer.onStopped()
        sendStopped(1, reason, description);

        // Wait until resumed
        try {
            pauseLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Apply any pending variable changes
        applyPendingVariableChanges();
    }

    // ========== Inspection ==========

    public JsonObject getStackFrames(int threadId) {
        JsonArray frames = new JsonArray();

        if (currentStep != null && currentRuntime != null) {
            JsonObject frame = new JsonObject();
            frame.addProperty("id", frameIdCounter.getAndIncrement());
            frame.addProperty("name", currentStep.getText());
            frame.addProperty("line", currentStep.getLine());
            frame.addProperty("column", 1);

            String relativePath = currentStep.getFeature().getResource().getRelativePath();
            String absolutePath = normalizeSourcePath(relativePath);

            frame.addProperty("sourcePath", absolutePath);
            frame.addProperty("sourceName", currentStep.getFeature().getName());

            frames.add(frame);
        }

        JsonObject result = new JsonObject();
        result.add("stackFrames", frames);
        return result;
    }

    public JsonObject getScopes(int frameId) {
        JsonArray scopes = new JsonArray();

        int varsRef = variableRefCounter.getAndIncrement();
        variableRefs.put(varsRef, "variables");

        JsonObject varsScope = new JsonObject();
        varsScope.addProperty("name", "Variables");
        varsScope.addProperty("variablesReference", varsRef);
        varsScope.addProperty("expensive", false);
        scopes.add(varsScope);

        JsonObject result = new JsonObject();
        result.add("scopes", scopes);
        return result;
    }

    public JsonObject getVariables(int variablesReference) {
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

        JsonObject result = new JsonObject();
        result.add("variables", variables);
        return result;
    }

    public JsonObject evaluate(int frameId, String expression, String context) {
        JsonObject result = new JsonObject();

        if (currentRuntime == null) {
            result.addProperty("result", "No active runtime");
            result.addProperty("type", "error");
            return result;
        }

        if (!paused) {
            result.addProperty("result", "Cannot evaluate while running");
            result.addProperty("type", "error");
            return result;
        }

        expression = expression.trim();
        log.trace("Evaluating expression: '{}' (context: {})", expression, context);

        try {
            if (expression.startsWith("match ")) {
                return evaluateMatch(expression.substring(6).trim());
            }

            if ("hover".equals(context) || "watch".equals(context)) {
                String rootVar = expression.split("[.\\[\\(]")[0].trim();
                if (!currentRuntime.engine.vars.containsKey(rootVar)) {
                    result.addProperty("result", "undefined");
                    result.addProperty("type", "undefined");
                    return result;
                }
            }

            Variable evalResult = currentRuntime.engine.evalKarateExpression(expression);

            if (evalResult == null || evalResult.isNull()) {
                result.addProperty("result", "null");
                result.addProperty("type", "null");
                return result;
            }

            Object value = evalResult.getValue();
            result.addProperty("result", formatValue(value));
            result.addProperty("type", value != null ? value.getClass().getSimpleName() : "null");
            return result;

        } catch (Exception e) {
            log.trace("Evaluation error: {}", e.getMessage());
            result.addProperty("result", "Error: " + e.getMessage());
            result.addProperty("type", "error");
            return result;
        }
    }

    private JsonObject evaluateMatch(String matchExpression) {
        JsonObject result = new JsonObject();

        try {
            Match.Type matchType = Match.Type.EQUALS;
            String lhs;
            String rhs;

            int opIndex = -1;
            String operator = null;

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
                result.addProperty("result", "Invalid match syntax. Use: match <expr> == <expected>");
                result.addProperty("type", "error");
                return result;
            }

            lhs = matchExpression.substring(0, opIndex).trim();
            rhs = matchExpression.substring(opIndex + operator.length() + 2).trim();

            String rootVar = lhs.split("[.\\[\\(]")[0].trim();
            if (!currentRuntime.engine.vars.containsKey(rootVar)) {
                result.addProperty("result", "Variable not defined: " + rootVar);
                result.addProperty("type", "error");
                return result;
            }

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

            Match.Result matchResult = currentRuntime.engine.match(matchType, lhs, null, rhs);

            if (matchResult.pass) {
                result.addProperty("result", "PASS");
            } else {
                result.addProperty("result", "FAIL: " + matchResult.message);
            }
            result.addProperty("type", "boolean");
            return result;

        } catch (Exception e) {
            log.trace("Match evaluation error: {}", e.getMessage());
            result.addProperty("result", "Match error: " + e.getMessage());
            result.addProperty("type", "error");
            return result;
        }
    }

    public JsonObject setVariable(int variablesReference, String name, String value) {
        JsonObject result = new JsonObject();

        if (currentRuntime == null) {
            result.addProperty("value", value);
            result.addProperty("type", "error");
            return result;
        }

        Object parsedValue = parseValue(value);
        pendingVariableChanges.put(name, parsedValue);

        String displayValue = formatValue(parsedValue);
        String type = parsedValue != null ? parsedValue.getClass().getSimpleName() : "null";

        log.debug("Queued variable change: {} = {}", name, displayValue);

        result.addProperty("value", displayValue);
        result.addProperty("type", type);
        return result;
    }

    private void applyPendingVariableChanges() {
        if (pendingVariableChanges.isEmpty() || currentRuntime == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : pendingVariableChanges.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            log.debug("Applying variable change: {} = {}", name, formatValue(value));
            currentRuntime.engine.setVariable(name, value);
        }

        pendingVariableChanges.clear();
    }

    // ========== Helpers ==========

    private String normalizeSourcePath(String path) {
        File file = new File(path);
        if (file.isAbsolute()) {
            log.trace("normalizeSourcePath: {} is absolute, returning as-is", path);
            return file.getAbsolutePath();
        }
        String[] sourceRoots = {
            "src/test/java/",
            "src/test/resources/",
            "src/main/java/",
            "src/main/resources/"
        };
        for (String root : sourceRoots) {
            File resolved = new File(workspaceRoot, root + path);
            log.trace("normalizeSourcePath: trying {} -> exists={}", resolved.getAbsolutePath(), resolved.exists());
            if (resolved.exists()) {
                return resolved.getAbsolutePath();
            }
        }
        String fallback = new File(workspaceRoot, path).getAbsolutePath();
        log.trace("normalizeSourcePath: no source root matched for {}, using fallback: {}", path, fallback);
        return fallback;
    }

    private String toClasspathPath(String absolutePath) {
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

        log.warn("Could not find source root in path: {}, using file path directly", absolutePath);
        return "file:" + absolutePath;
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Map || value instanceof List) {
            try {
                return new Gson().toJson(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    private Object parseValue(String value) {
        if (value == null || value.equals("null")) return null;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;

        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }

        if (value.startsWith("{") || value.startsWith("[")) {
            try {
                return com.intuit.karate.Json.of(value).value();
            } catch (Exception e) {
                log.warn("Failed to parse JSON value: {}", value, e);
                return value;
            }
        }

        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    // ========== IPC Event Sending ==========

    private void sendStopped(int threadId, String reason, String description) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        body.addProperty("reason", reason);
        if (description != null) {
            body.addProperty("description", description);
        }
        ipcServer.sendEvent(IpcEvents.STOPPED, body);
    }

    private void sendContinued(int threadId) {
        JsonObject body = new JsonObject();
        body.addProperty("threadId", threadId);
        body.addProperty("allThreadsContinued", true);
        ipcServer.sendEvent(IpcEvents.CONTINUED, body);
    }

    private void sendTerminated() {
        ipcServer.sendEvent(IpcEvents.TERMINATED, null);
    }

    private void sendOutput(String category, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("output", text + "\n");
        ipcServer.sendEvent(IpcEvents.OUTPUT, body);
    }
}

