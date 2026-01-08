package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Consumer;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;

/**
 * Status bar widget for switching Karate log level.
 * Displays the current log level and allows clicking to switch.
 */
public class KarateLogLevelWidget extends EditorBasedWidget implements StatusBarWidget.MultipleTextValuesPresentation {
    
    public static final String ID = "KarateLogLevel";
    
    public KarateLogLevelWidget(@NotNull Project project) {
        super(project);
    }
    
    @Override
    public @NonNls @NotNull String ID() {
        return ID;
    }
    
    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }
    
    @Override
    public @Nullable String getTooltipText() {
        return "Karate log level (click to change)";
    }
    
    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return null; // Using popup instead
    }
    
    @Override
    public @Nullable("null means the widget is unable to show the popup") ListPopup getPopup() {
        if (myProject.isDisposed()) return null;
        
        KarateProjectSettings settings = KarateProjectSettings.getInstance(myProject);
        List<String> levels = Arrays.asList(KarateProjectSettings.LOG_LEVELS);
        
        BaseListPopupStep<String> step = new BaseListPopupStep<>("Select Log Level", levels) {
            @Override
            public @Nullable PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                if (finalChoice && selectedValue != null) {
                    settings.logLevel = selectedValue;
                    updateWidget();
                }
                return FINAL_CHOICE;
            }
            
            @Override
            public boolean isSpeedSearchEnabled() {
                return true;
            }
        };
        
        return JBPopupFactory.getInstance().createListPopup(step);
    }
    
    @Override
    public @Nullable @NonNls String getSelectedValue() {
        if (myProject.isDisposed()) return null;
        KarateProjectSettings settings = KarateProjectSettings.getInstance(myProject);
        return "Log: " + settings.getEffectiveLogLevel();
    }
    
    private void updateWidget() {
        StatusBar statusBar = getStatusBar();
        if (statusBar != null) {
            statusBar.updateWidget(ID);
        }
    }
}

