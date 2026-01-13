package com.j8d.karate.intellij.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Program runner for the "Run" executor that shows a message to use Debug instead.
 * Karate Debug only supports debug mode.
 */
public class KarateRunProgramRunner extends GenericProgramRunner<RunnerSettings> {

    private static final String RUNNER_ID = "KarateRunRunner";

    @Override
    public @NotNull String getRunnerId() {
        return RUNNER_ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        // Handle the Run executor for Karate configurations
        return executorId.equals(DefaultRunExecutor.EXECUTOR_ID)
                && profile instanceof KarateRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                        @NotNull ExecutionEnvironment environment)
            throws ExecutionException {

        // Show notification that Run is not supported
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                        "Karate Debug",
                        "Run is not supported. Please use Debug instead.",
                        NotificationType.INFORMATION)
                .notify(environment.getProject());

        return null;
    }
}

