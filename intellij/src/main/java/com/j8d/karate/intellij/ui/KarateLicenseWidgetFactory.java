package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating the license status bar widget.
 */
public class KarateLicenseWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return KarateLicenseWidget.ID;
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Karate Debug License";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true; // Always show license widget
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new KarateLicenseWidget(project);
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

