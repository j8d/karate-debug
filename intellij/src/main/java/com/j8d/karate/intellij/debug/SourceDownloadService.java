package com.j8d.karate.intellij.debug;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Service for detecting missing library sources and downloading them.
 * Shows a notification to the user when sources are missing during debugging.
 */
@Service(Service.Level.PROJECT)
public final class SourceDownloadService {

    private static final Logger LOG = Logger.getInstance(SourceDownloadService.class);

    private final Project project;

    // Track if we've shown the "sources missing" notification this debug session.
    // We only show one notification per session to avoid spam.
    // Note: Access is single-threaded via EDT (invokeLater), so volatile is not required.
    private boolean hasShownSessionNotification = false;

    public SourceDownloadService(Project project) {
        this.project = project;
    }

    public static SourceDownloadService getInstance(@NotNull Project project) {
        return project.getService(SourceDownloadService.class);
    }

    /**
     * Check if a resolved file is a decompiled class file (sources missing).
     * @param resolvedFile The file resolved from JavaPsiFacade
     * @return true if this is a decompiled class file, false if it's a source file
     */
    public boolean isMissingSources(@Nullable VirtualFile resolvedFile) {
        if (resolvedFile == null) {
            return false;
        }

        String path = resolvedFile.getPath();

        // Compiled class files are decompiled views - sources are missing
        if (path.endsWith(".class")) {
            return true;
        }

        // Files from the decompiler virtual file system are missing sources
        String protocol = resolvedFile.getFileSystem().getProtocol();
        return "decompiler".equals(protocol);
    }

    /**
     * Called when we detect a class with missing sources.
     * Shows a notification to the user with an option to download sources.
     * Only shows one notification per debug session to avoid spam.
     */
    public void notifyMissingSources(String className) {
        // Only show one notification per debug session
        if (hasShownSessionNotification) {
            return;
        }
        hasShownSessionNotification = true;
        
        ApplicationManager.getApplication().invokeLater(() -> {
            // Extract simple class name for display (e.g., "ScenarioEngine" from "com.intuit.karate.core.ScenarioEngine")
            String simpleClassName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;

            var notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                    "Library Sources Missing",
                    "Source code for '" + simpleClassName + "' is not available. " +
                    "You're viewing decompiled code. Click 'Download Sources' to fetch library sources.",
                    NotificationType.INFORMATION
                );

            notification.addAction(new AnAction("Download Sources") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    notification.expire();
                    downloadSources();
                }
            });

            notification.addAction(new AnAction("Don't Show Again") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    notification.expire();
                    // Keep hasShownSessionNotification = true to prevent future notifications
                }
            });

            notification.notify(project);
        });
    }

    /**
     * Downloads library sources using Maven or Gradle.
     */
    public void downloadSources() {
        KarateProjectService projectService = KarateProjectService.getInstance(project);
        String projectType = projectService.getProjectType();
        
        if ("maven".equals(projectType)) {
            downloadMavenSources();
        } else if ("gradle".equals(projectType)) {
            downloadGradleSources();
        } else {
            showError("Unknown project type. Please run 'mvn dependency:sources' or equivalent manually.");
        }
    }

    private void downloadMavenSources() {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Downloading Library Sources", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Running mvn dependency:sources...");
                
                try {
                    String basePath = project.getBasePath();
                    if (basePath == null) {
                        showError("Cannot determine project base path");
                        return;
                    }
                    
                    GeneralCommandLine commandLine = new GeneralCommandLine("mvn", "dependency:sources")
                        .withWorkDirectory(new File(basePath));
                    
                    OSProcessHandler handler = new OSProcessHandler(commandLine);

                    handler.addProcessListener(new ProcessAdapter() {
                        @Override
                        public void processTerminated(@NotNull ProcessEvent event) {
                            if (event.getExitCode() == 0) {
                                showSuccess();
                            } else {
                                showError("Maven command failed. Check the Maven tool window for details.");
                            }
                        }
                    });
                    
                    handler.startNotify();
                    handler.waitFor();

                } catch (Exception e) {
                    LOG.error("Failed to download sources", e);
                    showError("Failed to run Maven: " + e.getMessage());
                }
            }
        });
    }

    private void downloadGradleSources() {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Downloading Library Sources", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Refreshing Gradle dependencies with sources...");

                try {
                    String basePath = project.getBasePath();
                    if (basePath == null) {
                        showError("Cannot determine project base path");
                        return;
                    }

                    // For Gradle, we need to use the IDEA sync which downloads sources
                    // The simplest approach is to tell the user to use the IDE's built-in refresh
                    ApplicationManager.getApplication().invokeLater(() -> {
                        var notification = NotificationGroupManager.getInstance()
                            .getNotificationGroup("Karate Debug")
                            .createNotification(
                                "Gradle Sources",
                                "For Gradle projects, please use View > Tool Windows > Gradle, " +
                                "then click the Refresh button. IntelliJ will download sources automatically " +
                                "if 'Download sources' is enabled in Gradle settings.",
                                NotificationType.INFORMATION
                            );
                        notification.notify(project);
                    });

                } catch (Exception e) {
                    LOG.error("Failed to handle Gradle sources", e);
                    showError("Failed: " + e.getMessage());
                }
            }
        });
    }

    private void showSuccess() {
        // Refresh the VFS to pick up newly downloaded source JARs
        VirtualFileManager.getInstance().asyncRefresh(() -> {
            // After VFS refresh, trigger a project reimport to attach sources to libraries
            ApplicationManager.getApplication().invokeLater(() -> {
                triggerProjectReimport();

                var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Karate Debug")
                    .createNotification(
                        "Sources Downloaded",
                        "Library sources have been downloaded and the project is being refreshed. " +
                        "Sources should be available shortly.",
                        NotificationType.INFORMATION
                    );
                notification.notify(project);
            });
        });
    }

    /**
     * Triggers a project reimport to pick up newly downloaded source JARs.
     * Tries Maven first, then falls back to the generic external system refresh.
     */
    private void triggerProjectReimport() {
        ActionManager actionManager = ActionManager.getInstance();

        // Try Maven-specific reimport first
        AnAction mavenReimport = actionManager.getAction("Maven.Reimport");
        if (mavenReimport != null) {
            LOG.debug("Triggering Maven.Reimport action");
            invokeAction(mavenReimport);
            return;
        }

        // Try generic external system refresh (works for both Maven and Gradle)
        AnAction externalRefresh = actionManager.getAction("ExternalSystem.RefreshAllProjects");
        if (externalRefresh != null) {
            LOG.debug("Triggering ExternalSystem.RefreshAllProjects action");
            invokeAction(externalRefresh);
            return;
        }

        LOG.debug("No reimport action found, sources may not be available until manual refresh");
    }

    /**
     * Invokes an action programmatically.
     */
    private void invokeAction(AnAction action) {
        DataContext dataContext = dataId -> {
            if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.is(dataId)) {
                return project;
            }
            return null;
        };

        AnActionEvent event = AnActionEvent.createEvent(
            action,
            dataContext,
            "SourceDownloadService",
            action.getTemplatePresentation().clone(),
            ActionManager.getInstance(),
            0
        );

        ActionUtil.performActionDumbAwareWithCallbacks(action, event);
    }

    private void showError(String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                    "Download Failed",
                    message,
                    NotificationType.ERROR
                );
            notification.notify(project);
        });
    }

    /**
     * Reset the notification state. Called when a new debug session starts.
     * This allows the notification to be shown again in a new session.
     */
    public void resetNotificationState() {
        hasShownSessionNotification = false;
    }
}
