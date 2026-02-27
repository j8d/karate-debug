package com.j8d.karate.intellij.project;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.j8d.karate.intellij.licensing.AnalyticsTracker;
import com.j8d.karate.intellij.licensing.LicenseManager;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity that detects Karate projects when the IDE opens.
 * Initializes project services, license manager, and shows notification if Karate is detected.
 */
public class KarateProjectStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(KarateProjectStartupActivity.class);
    private static final String LAST_VERSION_KEY = "karateDebug.lastVersion";

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LOG.info("Karate Debug: Starting project detection for " + project.getName());

        // Initialize license manager (application-level, only needs to happen once)
        LicenseManager licenseManager = LicenseManager.getInstance();
        licenseManager.initialize()
                .thenAccept(status -> LOG.info("License status: " + status));

        // Track lifecycle events (fire-and-forget)
        trackLifecycleEvent(licenseManager);

        // Initialize build file listener for auto-refresh
        KarateBuildFileListener.getInstance(project);

        // Wait for smart mode (indexing complete) before detecting Karate project
        DumbService.getInstance(project).runWhenSmart(() -> {
            // Run detection in background to avoid slow operations on EDT
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Detecting Karate project", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    detectAndNotify(project);
                }
            });
        });

        return Unit.INSTANCE;
    }

    private void detectAndNotify(Project project) {
        // Detect Karate project
        KarateProjectService service = KarateProjectService.getInstance(project);
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        // Force re-detection now that indexing is complete
        service.refresh();

        if (service.isKarateProject()) {
            LOG.info("Karate project detected: " + project.getName() +
                ", version=" + service.getKarateVersion() +
                ", type=" + service.getProjectType() +
                ", features=" + service.getFeatureFiles().size());

            // Show notification if enabled (must be on EDT)
            if (settings.showDetectionNotification) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    String message = buildNotificationMessage(service);
                    com.intellij.notification.Notification notification = NotificationGroupManager.getInstance()
                        .getNotificationGroup("Karate Debug")
                        .createNotification(
                            "Karate Debug",
                            message,
                            NotificationType.INFORMATION
                        );
                    notification.notify(project);

                    // Auto-expire after 5 seconds
                    com.intellij.util.Alarm alarm = new com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD);
                    alarm.addRequest(() -> notification.expire(), 5000);
                });
            }
        } else {
            LOG.info("Not a Karate project: " + project.getName());
        }
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

    /**
     * Track lifecycle events (initialized/updated) for analytics.
     * Uses PropertiesComponent to persist the last seen version across restarts.
     */
    private void trackLifecycleEvent(LicenseManager licenseManager) {
        try {
            String currentVersion = getPluginVersion();
            String lastVersion = com.intellij.ide.util.PropertiesComponent.getInstance()
                    .getValue(LAST_VERSION_KEY);

            AnalyticsTracker tracker = new AnalyticsTracker(
                    licenseManager.getMachineId(),
                    currentVersion
            );

            // Always send 'initialized' on every activation to track active installs
            tracker.trackLifecycleEvent(AnalyticsTracker.LifecycleEventType.INITIALIZED, null);

            // Additionally track version updates for adoption metrics
            if (lastVersion != null && !lastVersion.equals(currentVersion)) {
                tracker.trackLifecycleEvent(AnalyticsTracker.LifecycleEventType.UPDATED, lastVersion);
            }

            // Store current version for next activation
            com.intellij.ide.util.PropertiesComponent.getInstance()
                    .setValue(LAST_VERSION_KEY, currentVersion);
        } catch (Exception e) {
            LOG.debug("Failed to track lifecycle event", e);
        }
    }

    private static String getPluginVersion() {
        try {
            var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                    com.intellij.openapi.extensions.PluginId.getId("com.j8d.karate-debug"));
            return plugin != null ? plugin.getVersion() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

