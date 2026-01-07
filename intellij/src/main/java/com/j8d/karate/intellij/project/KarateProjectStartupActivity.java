package com.j8d.karate.intellij.project;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity that detects Karate projects when the IDE opens.
 * Initializes project services and shows notification if Karate is detected.
 */
public class KarateProjectStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(KarateProjectStartupActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LOG.info("Karate Debug: Starting project detection for " + project.getName());

        // Initialize build file listener for auto-refresh
        KarateBuildFileListener.getInstance(project);

        // Detect Karate project
        KarateProjectService service = KarateProjectService.getInstance(project);
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        if (service.isKarateProject()) {
            LOG.info("Karate project detected: " + project.getName() +
                ", version=" + service.getKarateVersion() +
                ", type=" + service.getProjectType() +
                ", features=" + service.getFeatureFiles().size());

            // Show notification if enabled
            if (settings.showDetectionNotification) {
                String message = buildNotificationMessage(service);
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Karate Debug")
                    .createNotification(
                        "Karate Debug",
                        message,
                        NotificationType.INFORMATION
                    )
                    .notify(project);
            }
        } else {
            LOG.info("Not a Karate project: " + project.getName());
        }

        return Unit.INSTANCE;
    }

    private String buildNotificationMessage(KarateProjectService service) {
        StringBuilder message = new StringBuilder("Karate project detected.");

        String version = service.getKarateVersion();
        if (version != null) {
            message.append(" Version: ").append(version).append(".");
        }

        int featureCount = service.getFeatureFiles().size();
        message.append(" Found ").append(featureCount).append(" feature file");
        if (featureCount != 1) {
            message.append("s");
        }
        message.append(".");

        return message.toString();
    }
}

