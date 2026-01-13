package com.j8d.karate.intellij.ui;

import com.intellij.openapi.actionSystem.*;
import com.j8d.karate.intellij.licensing.LicenseManager;
import com.j8d.karate.intellij.licensing.LicenseStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Action group for license-related actions in the status bar popup.
 */
public class LicenseActionGroup extends DefaultActionGroup {

    public LicenseActionGroup() {
        super();
        setPopup(true);

        LicenseManager manager = LicenseManager.getInstance();
        LicenseStatus status = manager.getStatus();

        // Show different actions based on status
        if (status.getStatus() == LicenseStatus.Status.ACTIVE) {
            // Pro user
            add(new LicenseInfoAction());
            addSeparator();
            add(new ManageSubscriptionAction());
            add(new SignOutAction());
        } else if (manager.getUserId() != null) {
            // Logged in but not active (trialing or expired)
            add(new LicenseInfoAction());
            addSeparator();
            add(new UpgradeToProAction());
            add(new ManageSubscriptionAction());
            add(new SignOutAction());
        } else {
            // Anonymous trial or no status
            add(new LicenseInfoAction());
            addSeparator();
            add(new UpgradeToProAction());
            add(new SignInAction());
        }
    }

    private static class LicenseInfoAction extends AnAction {
        LicenseInfoAction() {
            super("License Info");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LicenseStatus status = LicenseManager.getInstance().getStatus();
            String message;

            switch (status.getStatus()) {
                case ACTIVE:
                    String user = status.getGithubUsername();
                    message = "Karate Debug Pro\nLicensed to: " + (user != null ? user : "Unknown");
                    break;
                case TRIALING:
                    message = "Trial Mode\n" + status.getDaysRemaining() + " days remaining";
                    break;
                case EXPIRED:
                    message = "Trial Expired\nPlease purchase a license to continue.";
                    break;
                default:
                    message = "No license information available.";
            }

            com.intellij.openapi.ui.Messages.showInfoMessage(
                    e.getProject(),
                    message,
                    "Karate Debug License"
            );
        }
    }

    private static class UpgradeToProAction extends AnAction {
        UpgradeToProAction() {
            super("Upgrade to Pro");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LicenseManager.getInstance().startCheckout(e.getProject());
        }
    }

    private static class ManageSubscriptionAction extends AnAction {
        ManageSubscriptionAction() {
            super("Manage Subscription");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LicenseManager.getInstance().openSubscriptionPortal(e.getProject());
        }
    }

    private static class SignInAction extends AnAction {
        SignInAction() {
            super("Sign In with GitHub");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LicenseManager.getInstance().startGitHubLogin();
        }
    }

    private static class SignOutAction extends AnAction {
        SignOutAction() {
            super("Sign Out");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            LicenseManager.getInstance().logout();
        }
    }
}

