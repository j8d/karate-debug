package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import com.j8d.karate.intellij.run.KarateRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * XDebugProcess implementation that bridges to the Karate DAP server.
 * This is the core of the debugging integration.
 */
public class KarateDebugProcess extends XDebugProcess {

    // Custom console content types for syntax highlighting
    private static final ConsoleViewContentType KARATE_OUTPUT = new ConsoleViewContentType(
            "KARATE_OUTPUT",
            new TextAttributes(new JBColor(0x008000, 0x6A8759), null, null, null, 0));  // Green
    private static final ConsoleViewContentType KARATE_SUCCESS = new ConsoleViewContentType(
            "KARATE_SUCCESS",
            new TextAttributes(new JBColor(0x008000, 0x6A8759), null, null, null, 1));  // Green bold
    private static final ConsoleViewContentType KARATE_STOPPED = new ConsoleViewContentType(
            "KARATE_STOPPED",
            new TextAttributes(new JBColor(0x0000FF, 0x6897BB), null, null, null, 1));  // Blue bold

    private final KarateRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private final KarateDapClient dapClient;
    private final MatchDiagnosticsService matchDiagnosticsService;
    private ConsoleView consoleView;

    public KarateDebugProcess(@NotNull XDebugSession session,
                               @NotNull KarateRunConfiguration configuration,
                               @NotNull ExecutionEnvironment environment) throws ExecutionException {
        super(session);
        this.configuration = configuration;
        this.environment = environment;
        this.dapClient = new KarateDapClient(this);
        this.matchDiagnosticsService = new MatchDiagnosticsService(session.getProject(), dapClient);

        // Register match diagnostics as a session listener
        session.addSessionListener(matchDiagnosticsService);
    }

    @Override
    public void sessionInitialized() {
        super.sessionInitialized();

        // Reset missing sources notification state for new session
        SourceDownloadService.getInstance(getSession().getProject()).resetNotificationState();

        // Start the DAP server and connect
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                log("Starting Karate debug session...");
                log("Feature: " + configuration.getFeatureFile());

                dapClient.start(configuration);

            } catch (Exception e) {
                log("Error starting debug session: " + e.getMessage());
                ApplicationManager.getApplication().invokeLater(() -> {
                    getSession().stop();
                });
            }
        });
    }

    @Override
    public void stop() {
        dapClient.stop();
        matchDiagnosticsService.dispose();
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        dapClient.sendStepOver();
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        dapClient.sendStepInto();
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        dapClient.sendStepOut();
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        dapClient.sendContinue();
    }

    @Override
    public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
        // TODO: Implement run-to-cursor
        resume(context);
    }

    @Override
    public @NotNull XBreakpointHandler<?>[] getBreakpointHandlers() {
        return new XBreakpointHandler[]{
            new KarateBreakpointHandler(this)
        };
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return new KarateEditorsProvider();
    }

    @Override
    public @NotNull ExecutionConsole createConsole() {
        consoleView = (ConsoleView) super.createConsole();
        return consoleView;
    }

    /**
     * Called when the debugger stops (breakpoint hit, step complete, etc.)
     * The debug server logs detailed stop info including line and condition.
     */
    public void onStopped(int threadId, String reason) {
        // Fetch stack trace and create suspend context
        dapClient.getStackTrace().thenAccept(response -> {
            // Create suspend context on background thread to avoid slow operations on EDT.
            // Source file resolution in KarateStackFrame can involve file system lookups
            // and PSI queries which are slow operations prohibited on EDT.
            XSuspendContext suspendContext = createSuspendContext(threadId, response);
            ApplicationManager.getApplication().invokeLater(() -> {
                getSession().positionReached(suspendContext);
            });
        }).exceptionally(e -> {
            log("Error getting stack trace: " + e.getMessage());
            return null;
        });
    }

    private XSuspendContext createSuspendContext(int threadId, JsonObject stackTraceResponse) {
        return new KarateSuspendContext(this, threadId, stackTraceResponse);
    }

    public void log(String message) {
        if (consoleView != null && message != null) {
            // Skip empty messages
            if (message.trim().isEmpty()) {
                return;
            }

            // Check if message should be filtered out
            KarateProjectSettings settings = KarateProjectSettings.getInstance(getSession().getProject());
            if (settings.shouldFilterLog(message)) {
                return;
            }

            ConsoleViewContentType contentType = getContentType(message);
            ApplicationManager.getApplication().invokeLater(() -> {
                consoleView.print(message + "\n", contentType);
            });
        }
    }

    /**
     * Determines the appropriate console content type based on the log message content.
     */
    private ConsoleViewContentType getContentType(String message) {
        if (message == null) {
            return ConsoleViewContentType.NORMAL_OUTPUT;
        }

        // Error patterns - red
        if (message.contains("ERROR") || message.contains("Exception") ||
            message.contains("FAILED") || message.contains("failed:")) {
            return ConsoleViewContentType.ERROR_OUTPUT;
        }

        // Warning patterns - yellow
        if (message.contains("WARN")) {
            return ConsoleViewContentType.LOG_WARNING_OUTPUT;
        }

        // Stopped/breakpoint messages - blue bold
        if (message.startsWith("Stopped:")) {
            return KARATE_STOPPED;
        }

        // Success patterns - green bold
        if (message.contains("passed:") || message.contains("PASSED")) {
            return KARATE_SUCCESS;
        }

        // Karate log output - green
        if (message.contains("[karate.log]") || message.contains("[print]")) {
            return KARATE_OUTPUT;
        }

        // System/debug messages - gray
        if (message.startsWith("[Karate Debug]") || message.contains("DEBUG")) {
            return ConsoleViewContentType.SYSTEM_OUTPUT;
        }

        return ConsoleViewContentType.NORMAL_OUTPUT;
    }

    public KarateDapClient getDapClient() {
        return dapClient;
    }

    public KarateRunConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * XSuspendContext implementation for Karate debugging.
     */
    private static class KarateSuspendContext extends XSuspendContext {
        private final KarateExecutionStack executionStack;

        public KarateSuspendContext(KarateDebugProcess process, int threadId, JsonObject stackTraceResponse) {
            this.executionStack = new KarateExecutionStack(process, threadId, stackTraceResponse);
        }

        @Override
        public @Nullable XExecutionStack getActiveExecutionStack() {
            return executionStack;
        }

        @Override
        public XExecutionStack @NotNull [] getExecutionStacks() {
            return new XExecutionStack[]{executionStack};
        }
    }
}

