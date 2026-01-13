package com.j8d.karate.intellij.ui;

import com.intellij.ide.DataManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Consumer;
import com.j8d.karate.intellij.licensing.LicenseManager;
import com.j8d.karate.intellij.licensing.LicenseStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

/**
 * Status bar widget showing license status.
 */
public class KarateLicenseWidget extends EditorBasedWidget
        implements StatusBarWidget.MultipleTextValuesPresentation,
                   LicenseManager.LicenseStatusListener {

    public static final String ID = "KarateLicense";

    private LicenseStatus currentStatus = LicenseStatus.none();

    public KarateLicenseWidget(@NotNull Project project) {
        super(project);
        LicenseManager.getInstance().addListener(this);
        this.currentStatus = LicenseManager.getInstance().getStatus();
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public void onStatusChanged(LicenseStatus status) {
        this.currentStatus = status;
        if (myStatusBar != null) {
            myStatusBar.updateWidget(ID);
        }
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @Nullable String getTooltipText() {
        switch (currentStatus.getStatus()) {
            case ACTIVE:
                String username = currentStatus.getGithubUsername();
                return username != null
                        ? "Licensed to " + username
                        : "Karate Debug Pro - Active";
            case TRIALING:
                return "Trial: " + currentStatus.getDaysRemaining() + " days remaining. Click for options.";
            case EXPIRED:
                return "Trial expired. Click to purchase license.";
            default:
                return "Karate Debug - Click for license info";
        }
    }

    @Override
    public @Nullable String getSelectedValue() {
        switch (currentStatus.getStatus()) {
            case ACTIVE:
                return "Karate Pro";
            case TRIALING:
                int days = currentStatus.getDaysRemaining();
                if (days <= 3) {
                    return "Trial: " + days + "d!";
                }
                return "Trial: " + days + "d";
            case EXPIRED:
                return "Trial Expired";
            default:
                return "Karate Debug";
        }
    }

    @Override
    public @Nullable Icon getIcon() {
        return null; // Text-only widget
    }

    @Override
    public @Nullable ListPopup getPopup() {
        return JBPopupFactory.getInstance().createActionGroupPopup(
                "Karate Debug License",
                new LicenseActionGroup(),
                DataManager.getInstance().getDataContext(myStatusBar.getComponent()),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false
        );
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return null; // Using popup instead
    }

    @Override
    public void dispose() {
        LicenseManager.getInstance().removeListener(this);
        super.dispose();
    }
}

