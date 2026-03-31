package com.j8d.karate.intellij.actions;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to reset the pricing notification flag.
 * This allows developers to test the pricing notification multiple times.
 * 
 * To use: Tools -> Karate Debug -> Reset Pricing Notification (Test)
 */
public class TestResetPricingNotificationAction extends AnAction {

    private static final String KEY_PRICING_NOTIFICATION_SHOWN = "karateDebug.pricingNotificationShown_v0.2.3";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        properties.setValue(KEY_PRICING_NOTIFICATION_SHOWN, false);
        
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                        "Pricing notification flag cleared. Restart IDE to test notification.",
                        NotificationType.INFORMATION
                )
                .notify(e.getProject());
    }
}

