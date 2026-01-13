package com.j8d.karate.intellij.debug;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.j8d.karate.intellij.licensing.LicenseManager;
import com.j8d.karate.intellij.run.KarateRunConfiguration;
import com.j8d.karate.intellij.run.KarateRunProfileState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Program runner for Karate debug sessions.
 * Creates the XDebugProcess that bridges to the DAP server.
 */
public class KarateDebugProgramRunner extends GenericProgramRunner<RunnerSettings> {

    private static final String RUNNER_ID = "KarateDebugRunner";

    @Override
    public @NotNull String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return executorId.equals(DefaultDebugExecutor.EXECUTOR_ID)
            && profile instanceof KarateRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                        @NotNull ExecutionEnvironment environment)
            throws ExecutionException {

        // Check license before allowing debug
        LicenseManager licenseManager = LicenseManager.getInstance();
        if (!licenseManager.isLicenseValid()) {
            showTrialExpiredNotification(environment.getProject());
            return null;
        }

        if (!(state instanceof KarateRunProfileState)) {
            throw new ExecutionException("Invalid run profile state");
        }

        KarateRunProfileState karateState = (KarateRunProfileState) state;
        KarateRunConfiguration configuration = karateState.getConfiguration();

        XDebugSession session = XDebuggerManager.getInstance(environment.getProject())
            .startSession(environment, new XDebugProcessStarter() {
                @Override
                public @NotNull XDebugProcess start(@NotNull XDebugSession session)
                        throws ExecutionException {
                    return new KarateDebugProcess(session, configuration, environment);
                }
            });

        return session.getRunContentDescriptor();
    }

    private void showTrialExpiredNotification(Project project) {
        com.intellij.notification.Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Karate Debug")
            .createNotification(
                "Trial Expired",
                "Your Karate Debug trial has expired. Please purchase a license to continue debugging.",
                NotificationType.WARNING
            );

        notification.addAction(new com.intellij.openapi.actionSystem.AnAction("Purchase License") {
            @Override
            public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                LicenseManager.getInstance().startCheckout(project);
                notification.expire();
            }
        });

        notification.notify(project);
    }
}

