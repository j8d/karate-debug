package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for the Karate tool window.
 * Shows a tree view of all feature files and scenarios.
 * Implements DumbAware to be available during indexing.
 */
public class KarateToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        KarateToolWindowContent content = new KarateToolWindowContent(project);
        Content toolWindowContent = ContentFactory.getInstance()
            .createContent(content.getPanel(), "Features", false);
        toolWindow.getContentManager().addContent(toolWindowContent);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // Always make tool window available - detection happens in background
        // and the tool window will show "Not a Karate project" if not detected
        // This is better than hiding it completely before detection completes
        return true;
    }
}

