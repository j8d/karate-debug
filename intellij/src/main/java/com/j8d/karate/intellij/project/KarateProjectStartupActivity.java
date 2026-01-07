package com.j8d.karate.intellij.project;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup activity that detects Karate projects when the IDE opens.
 */
public class KarateProjectStartupActivity implements ProjectActivity {
    
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        KarateProjectService service = KarateProjectService.getInstance(project);
        
        if (service.isKarateProject()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                    "Karate Debug",
                    "Karate project detected. Debug features are now available.",
                    NotificationType.INFORMATION
                )
                .notify(project);
        }
        
        return Unit.INSTANCE;
    }
}

