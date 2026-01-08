package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.j8d.karate.intellij.run.KarateRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * XDebugProcess implementation that bridges to the Karate DAP server.
 * This is the core of the debugging integration.
 */
public class KarateDebugProcess extends XDebugProcess {

    private final KarateRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private final KarateDapClient dapClient;
    private ConsoleView consoleView;

    public KarateDebugProcess(@NotNull XDebugSession session,
                               @NotNull KarateRunConfiguration configuration,
                               @NotNull ExecutionEnvironment environment) throws ExecutionException {
        super(session);
        this.configuration = configuration;
        this.environment = environment;
        this.dapClient = new KarateDapClient(this);
    }

    @Override
    public void sessionInitialized() {
        super.sessionInitialized();

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
     */
    public void onStopped(int threadId, String reason) {
        log("Stopped: " + reason);

        // Fetch stack trace and create suspend context
        dapClient.getStackTrace().thenAccept(response -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                XSuspendContext suspendContext = createSuspendContext(threadId, response);
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
        if (consoleView != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                consoleView.print(message + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            });
        }
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

