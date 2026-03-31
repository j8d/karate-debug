package com.j8d.karate.intellij.licensing;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ide.BuiltInServerManager;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Application-level service for managing Karate Debug licensing.
 * Handles anonymous trials, GitHub authentication, and license validation.
 */
@Service(Service.Level.APP)
public final class LicenseManager {

    private static final Logger LOG = Logger.getInstance(LicenseManager.class);

    // PropertiesComponent keys (persisted across restarts)
    private static final String KEY_USER_ID = "karateDebug.userId";
    private static final String KEY_GITHUB_USERNAME = "karateDebug.githubUsername";
    private static final String KEY_TRIAL_START = "karateDebug.trialStartTimestamp";
    private static final String KEY_PRICING_NOTIFICATION_SHOWN = "karateDebug.pricingNotificationShown_v0.2.3";

    private final LicenseApiClient apiClient;
    private final String machineId;
    private final String machineName;

    private LicenseStatus currentStatus = LicenseStatus.none();
    private final List<LicenseStatusListener> listeners = new ArrayList<>();

    public interface LicenseStatusListener {
        void onStatusChanged(LicenseStatus status);
    }

    public LicenseManager() {
        this.apiClient = new LicenseApiClient();
        this.machineId = generateMachineId();
        this.machineName = getHostname();
        LOG.info("LicenseManager initialized with machineId: " + machineId);
    }

    public static LicenseManager getInstance() {
        return ApplicationManager.getApplication().getService(LicenseManager.class);
    }

    public void addListener(LicenseStatusListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LicenseStatusListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (LicenseStatusListener listener : listeners) {
            listener.onStatusChanged(currentStatus);
        }
    }

    // === Machine ID Generation ===

    private String generateMachineId() {
        try {
            String hostname = getHostname();
            String username = System.getProperty("user.name", "unknown");
            String os = System.getProperty("os.name", "unknown");
            String arch = System.getProperty("os.arch", "unknown");

            String raw = "karate-debug-" + hostname + "-" + username + "-" + os + "-" + arch;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) { // First 32 hex chars
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            LOG.warn("Failed to generate machine ID", e);
            return "unknown-" + System.currentTimeMillis();
        }
    }

    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // === Storage ===

    private PropertiesComponent getStorage() {
        return PropertiesComponent.getInstance();
    }

    @Nullable
    public String getUserId() {
        return getStorage().getValue(KEY_USER_ID);
    }

    private void setUserId(@Nullable String userId) {
        if (userId != null) {
            getStorage().setValue(KEY_USER_ID, userId);
        } else {
            getStorage().unsetValue(KEY_USER_ID);
        }
    }

    @Nullable
    public String getGithubUsername() {
        return getStorage().getValue(KEY_GITHUB_USERNAME);
    }

    private void setGithubUsername(@Nullable String username) {
        if (username != null) {
            getStorage().setValue(KEY_GITHUB_USERNAME, username);
        } else {
            getStorage().unsetValue(KEY_GITHUB_USERNAME);
        }
    }

    private long getTrialStartTimestamp() {
        return getStorage().getLong(KEY_TRIAL_START, 0L);
    }

    private void setTrialStartTimestamp(long timestamp) {
        getStorage().setValue(KEY_TRIAL_START, String.valueOf(timestamp));
    }

    // === Public API ===

    public LicenseStatus getStatus() {
        return currentStatus;
    }

    public boolean isLicenseValid() {
        return currentStatus.isValid();
    }

    public String getMachineId() {
        return machineId;
    }

    /**
     * Initialize license status. Call on startup.
     */
    public CompletableFuture<LicenseStatus> initialize() {
        String userId = getUserId();

        if (userId != null) {
            // User is logged in - validate their subscription
            return validateLicense().thenApply(status -> {
                // Show pricing notification to existing users (one-time)
                showPricingNotificationIfNeeded(userId);
                return status;
            });
        } else {
            // Start/check anonymous trial
            return startOrCheckAnonymousTrial();
        }
    }

    /**
     * Show one-time pricing notification to existing users about price reduction.
     */
    private void showPricingNotificationIfNeeded(@NotNull String userId) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        boolean hasShown = properties.getBoolean(KEY_PRICING_NOTIFICATION_SHOWN, false);

        if (hasShown) {
            return; // Already shown
        }

        // Mark as shown immediately to prevent duplicate notifications
        properties.setValue(KEY_PRICING_NOTIFICATION_SHOWN, true);

        // Show the notification
        NotificationGroupManager.getInstance()
                .getNotificationGroup("Karate Debug")
                .createNotification(
                        "Karate Debug Price Reduction",
                        "🎉 Great news! Karate Debug is now $9.99 (previously $29.99). Purchase today to unlock unlimited debugging!",
                        NotificationType.INFORMATION
                )
                .addAction(new com.intellij.openapi.actionSystem.AnAction("Learn More") {
                    @Override
                    public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                        BrowserUtil.browse("https://www.karatedebug.com/?pricing=announcement&ide=intellij");
                    }
                })
                .addAction(new com.intellij.openapi.actionSystem.AnAction("Purchase Now") {
                    @Override
                    public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                        startCheckout(null);
                    }
                })
                .notify(null);
    }

    /**
     * Start or check anonymous machine-based trial.
     */
    private CompletableFuture<LicenseStatus> startOrCheckAnonymousTrial() {
        return apiClient.startTrial(machineId, machineName)
                .thenApply(response -> {
                    if (response == null) {
                        // Offline - check local fallback
                        return checkLocalTrialFallback();
                    }

                    // Store trial start for offline fallback
                    if (response.startedAt != null) {
                        try {
                            long startTime = java.time.Instant.parse(response.startedAt).toEpochMilli();
                            setTrialStartTimestamp(startTime);
                        } catch (Exception e) {
                            LOG.warn("Failed to parse trial start date", e);
                        }
                    }

                    int days = response.daysRemaining != null ? response.daysRemaining : 0;
                    boolean isTrialing = "trialing".equals(response.status);

                    currentStatus = new LicenseStatus(
                            isTrialing && days > 0,
                            isTrialing ? LicenseStatus.Status.TRIALING : LicenseStatus.Status.EXPIRED,
                            days,
                            response.expiresAt,
                            null
                    );

                    notifyListeners();
                    return currentStatus;
                });
    }

    /**
     * Fallback for offline trial check.
     */
    private LicenseStatus checkLocalTrialFallback() {
        long trialStart = getTrialStartTimestamp();

        if (trialStart == 0) {
            currentStatus = LicenseStatus.none();
            notifyListeners();
            return currentStatus;
        }

        long now = System.currentTimeMillis();
        long trialEnd = trialStart + (LicenseConfig.TRIAL_DAYS * 24L * 60 * 60 * 1000);
        int daysRemaining = Math.max(0, (int) Math.ceil((trialEnd - now) / (24.0 * 60 * 60 * 1000)));

        currentStatus = daysRemaining > 0
                ? LicenseStatus.trialing(daysRemaining, null)
                : LicenseStatus.expired();

        notifyListeners();
        return currentStatus;
    }

    /**
     * Validate license for logged-in user.
     */
    public CompletableFuture<LicenseStatus> validateLicense() {
        String userId = getUserId();

        if (userId == null) {
            currentStatus = LicenseStatus.none();
            notifyListeners();
            return CompletableFuture.completedFuture(currentStatus);
        }

        return apiClient.validateLicense(userId, machineId, machineName)
                .thenApply(response -> {
                    if (response == null) {
                        // Offline - use cached status or expired
                        return checkLocalTrialFallback();
                    }

                    int days = response.daysRemaining != null ? response.daysRemaining : 0;
                    LicenseStatus.Status status;

                    switch (response.status != null ? response.status : "expired") {
                        case "active":
                            status = LicenseStatus.Status.ACTIVE;
                            break;
                        case "trialing":
                            status = LicenseStatus.Status.TRIALING;
                            break;
                        default:
                            status = LicenseStatus.Status.EXPIRED;
                    }

                    currentStatus = new LicenseStatus(
                            response.valid,
                            status,
                            days,
                            response.expiresAt,
                            getGithubUsername()
                    );

                    notifyListeners();
                    return currentStatus;
                });
    }

    /**
     * Complete GitHub OAuth flow with auth code.
     */
    public CompletableFuture<Boolean> completeGitHubAuth(@NotNull String code) {
        return apiClient.authenticateWithGitHub(code, machineId)
                .thenCompose(response -> {
                    if (response == null || response.userId == null) {
                        return CompletableFuture.completedFuture(false);
                    }

                    setUserId(response.userId);
                    setGithubUsername(response.githubUsername);

                    // Validate license after auth
                    return validateLicense().thenApply(status -> true);
                });
    }

    /**
     * Start GitHub OAuth flow - opens browser.
     * Uses the built-in IDE HTTP server for the OAuth callback.
     * The callback URL is: http://localhost:<port>/api/karate-debug/auth/callback
     */
    public void startGitHubLogin() {
        try {
            // Get the built-in server port
            int port = BuiltInServerManager.getInstance().getPort();
            String localCallback = "http://localhost:" + port + "/api/karate-debug/auth/callback";

            // The redirect goes to our API first, which will redirect back to the local callback
            String redirectUri = URLEncoder.encode(
                    LicenseConfig.API_BASE_URL + "/auth/callback", StandardCharsets.UTF_8.name());
            String localCallbackEncoded = URLEncoder.encode(localCallback, StandardCharsets.UTF_8.name());

            String authUrl = "https://github.com/login/oauth/authorize" +
                    "?client_id=" + LicenseConfig.GITHUB_CLIENT_ID +
                    "&scope=user:email" +
                    "&redirect_uri=" + redirectUri +
                    "&state=intellij:" + localCallbackEncoded;  // Pass local callback in state

            LOG.info("Starting GitHub OAuth with callback port: " + port);
            BrowserUtil.browse(authUrl);
        } catch (Exception e) {
            LOG.error("Failed to start GitHub login", e);
        }
    }

    /**
     * Complete GitHub login after receiving auth code via protocol handler.
     */
    public void completeGitHubLogin(@NotNull String code) {
        LOG.info("Completing GitHub login with code");

        apiClient.authenticateWithGitHub(code, machineId).thenAccept(response -> {
            if (response != null) {
                setUserId(response.userId);
                setGithubUsername(response.githubUsername);

                // Now validate the license
                validateLicense().thenAccept(status -> {
                    showNotification(null, "Logged in as " + response.githubUsername,
                            NotificationType.INFORMATION);
                });
            } else {
                showNotification(null, "GitHub login failed", NotificationType.ERROR);
            }
        });
    }

    /**
     * Logout and deactivate machine.
     * After logout, re-checks anonymous trial status.
     */
    public CompletableFuture<Void> logout() {
        String userId = getUserId();

        CompletableFuture<Void> deactivateFuture = userId != null
                ? apiClient.deactivateMachine(userId, machineId)
                : CompletableFuture.completedFuture(null);

        return deactivateFuture.thenCompose(v -> {
            setUserId(null);
            setGithubUsername(null);

            // Re-check anonymous trial status after logout
            return initialize();
        }).thenApply(status -> null);
    }

    /**
     * Open Stripe checkout for purchase.
     */
    public void startCheckout(@Nullable Project project) {
        String userId = getUserId();

        if (userId == null) {
            // Need to login first - silently start login flow
            startGitHubLogin();
            return;
        }

        apiClient.getCheckoutUrl(userId).thenAccept(url -> {
            if (url != null) {
                BrowserUtil.browse(url);
            } else {
                showNotification(project, "Failed to start checkout", NotificationType.ERROR);
            }
        });
    }

    /**
     * Open Stripe portal for subscription management.
     */
    public void openSubscriptionPortal(@Nullable Project project) {
        String userId = getUserId();

        if (userId == null) {
            showNotification(project, "Please sign in first", NotificationType.WARNING);
            return;
        }

        apiClient.getPortalUrl(userId).thenAccept(url -> {
            if (url != null) {
                BrowserUtil.browse(url);
            } else {
                showNotification(project, "Failed to open subscription portal", NotificationType.ERROR);
            }
        });
    }

    private void showNotification(@Nullable Project project, String message, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            com.intellij.notification.Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Karate Debug")
                    .createNotification("Karate Debug", message, type);

            // Auto-expire after 5 seconds for info/warning, 10 seconds for errors
            int expireMs = type == NotificationType.ERROR ? 10000 : 5000;
            com.intellij.openapi.Disposable disposable = com.intellij.openapi.util.Disposer.newDisposable("notification-alarm");
            com.intellij.util.Alarm alarm = new com.intellij.util.Alarm(disposable);
            alarm.addRequest(() -> {
                notification.expire();
                com.intellij.openapi.util.Disposer.dispose(disposable);
            }, expireMs);

            notification.notify(project);
        });
    }
}

