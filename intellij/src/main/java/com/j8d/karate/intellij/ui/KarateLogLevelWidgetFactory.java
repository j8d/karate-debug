package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for Karate Log Level status bar widget.
 */
public class KarateLogLevelWidgetFactory implements StatusBarWidgetFactory {
    
    @Override
    public @NonNls @NotNull String getId() {
        return KarateLogLevelWidget.ID;
    }
    
    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Karate Log Level";
    }
    
    @Override
    public boolean isAvailable(@NotNull Project project) {
        // Show only for Karate projects
        KarateProjectService service = project.getService(KarateProjectService.class);
        return service != null && service.isKarateProject();
    }
    
    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new KarateLogLevelWidget(project);
    }
    
    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }
    
    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }
}

