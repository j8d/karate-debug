package com.j8d.karate.intellij.licensing;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Karate Debug licensing API.
 */
public class LicenseApiClient {

    private static final Logger LOG = Logger.getInstance(LicenseApiClient.class);
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public LicenseApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Start or check anonymous trial.
     */
    public CompletableFuture<TrialResponse> startTrial(@NotNull String machineId, @NotNull String machineName) {
        JsonObject body = new JsonObject();
        body.addProperty("machineId", machineId);
        body.addProperty("machineName", machineName);
        body.addProperty("platform", "intellij");

        return post("/trial/start", body)
                .thenApply(response -> GSON.fromJson(response, TrialResponse.class))
                .exceptionally(e -> {
                    LOG.warn("Failed to start trial", e);
                    return null;
                });
    }

    /**
     * Validate license for authenticated user.
     */
    public CompletableFuture<ValidateResponse> validateLicense(@NotNull String userId,
                                                                @NotNull String machineId,
                                                                @Nullable String machineName) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("machineId", machineId);
        if (machineName != null) {
            body.addProperty("machineName", machineName);
        }
        body.addProperty("platform", "intellij");

        return post("/license/validate", body)
                .thenApply(response -> GSON.fromJson(response, ValidateResponse.class))
                .exceptionally(e -> {
                    LOG.warn("Failed to validate license", e);
                    return null;
                });
    }

    /**
     * Authenticate with GitHub OAuth code.
     */
    public CompletableFuture<AuthResponse> authenticateWithGitHub(@NotNull String code,
                                                                   @NotNull String machineId) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("machineId", machineId);

        return post("/auth/github", body)
                .thenApply(response -> GSON.fromJson(response, AuthResponse.class))
                .exceptionally(e -> {
                    LOG.warn("GitHub auth failed", e);
                    return null;
                });
    }

    /**
     * Deactivate machine on logout.
     */
    public CompletableFuture<Void> deactivateMachine(@NotNull String userId, @NotNull String machineId) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("machineId", machineId);

        return post("/license/deactivate", body)
                .<Void>thenApply(response -> null)
                .exceptionally(e -> {
                    LOG.warn("Failed to deactivate machine", e);
                    return null;
                });
    }

    /**
     * Get Stripe checkout URL.
     */
    public CompletableFuture<String> getCheckoutUrl(@NotNull String userId) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);

        return post("/subscription/checkout", body)
                .thenApply(response -> {
                    JsonObject json = GSON.fromJson(response, JsonObject.class);
                    return json.has("url") ? json.get("url").getAsString() : null;
                })
                .exceptionally(e -> {
                    LOG.warn("Failed to get checkout URL", e);
                    return null;
                });
    }

    /**
     * Get Stripe portal URL for subscription management.
     */
    public CompletableFuture<String> getPortalUrl(@NotNull String userId) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);

        return post("/subscription/portal", body)
                .thenApply(response -> {
                    JsonObject json = GSON.fromJson(response, JsonObject.class);
                    return json.has("url") ? json.get("url").getAsString() : null;
                })
                .exceptionally(e -> {
                    LOG.warn("Failed to get portal URL", e);
                    return null;
                });
    }

    private CompletableFuture<String> post(String endpoint, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LicenseConfig.API_BASE_URL + endpoint))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Karate-Debug-IntelliJ")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return response.body();
                    }
                    throw new RuntimeException("API error: " + response.statusCode() + " - " + response.body());
                });
    }
}
