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
import com.j8d.karate.intellij.project.KarateProjectService;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Status bar widget for switching Karate environments.
 * Displays the current environment and allows clicking to switch.
 * Only shows for Karate projects.
 */
public class KarateEnvironmentWidget extends EditorBasedWidget implements StatusBarWidget.MultipleTextValuesPresentation {

    public static final String ID = "KarateEnvironment";
    private final Runnable updateListener;

    public KarateEnvironmentWidget(@NotNull Project project) {
        super(project);
        // Listen for project detection and settings changes to update widget
        updateListener = this::updateWidget;
        KarateProjectService.getInstance(project).addDetectionListener(updateListener);
        KarateProjectSettings.getInstance(project).addChangeListener(updateListener);
    }

    @Override
    public void dispose() {
        Project project = getProject();
        if (project != null && !project.isDisposed()) {
            KarateProjectService.getInstance(project).removeDetectionListener(updateListener);
            KarateProjectSettings.getInstance(project).removeChangeListener(updateListener);
        }
        super.dispose();
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
        return "Current Karate environment (click to change)";
    }
    
    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return null; // Using popup instead
    }
    
    @Override
    public @Nullable("null means the widget is unable to show the popup") ListPopup getPopup() {
        Project project = getProject();
        if (project == null || project.isDisposed()) return null;

        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
        List<String> environments = settings.getEnvironmentsList();
        
        BaseListPopupStep<String> step = new BaseListPopupStep<>("Select Environment", environments) {
            @Override
            public @Nullable PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                if (finalChoice && selectedValue != null) {
                    settings.defaultEnvironment = selectedValue;
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
        Project project = getProject();
        if (project == null || project.isDisposed()) return null;

        // Only show for Karate projects
        KarateProjectService service = KarateProjectService.getInstance(project);
        if (!service.isKarateProject()) {
            return null; // Hides the widget
        }

        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
        return "Env: " + settings.getEffectiveEnvironment();
    }
    
    private void updateWidget() {
        StatusBar statusBar = getStatusBar();
        if (statusBar != null) {
            statusBar.updateWidget(ID);
        }
    }
}

