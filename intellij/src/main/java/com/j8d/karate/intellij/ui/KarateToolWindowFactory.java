package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the Karate tool window.
 * Shows a tree view of all feature files and scenarios.
 */
public class KarateToolWindowFactory implements ToolWindowFactory {
    
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        KarateToolWindowContent content = new KarateToolWindowContent(project);
        Content toolWindowContent = ContentFactory.getInstance()
            .createContent(content.getPanel(), "Features", false);
        toolWindow.getContentManager().addContent(toolWindowContent);
    }
    
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // Only show tool window for Karate projects
        return KarateProjectService.getInstance(project).isKarateProject();
    }
}

