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
 * Fire-and-forget analytics tracker for debug sessions and lifecycle events.
 * All methods are non-blocking and failures are silently logged.
 */
public class AnalyticsTracker {

    private static final Logger LOG = Logger.getInstance(AnalyticsTracker.class);
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String machineId;
    private final String clientVersion;

    // Current session tracking
    private String currentSessionId;
    private long sessionStartTime;

    public enum SessionOutcome {
        COMPLETED("completed"),
        STOPPED("stopped"),
        CRASHED("crashed"),
        TIMEOUT("timeout");

        private final String value;

        SessionOutcome(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum LifecycleEventType {
        INITIALIZED("initialized"),
        UPDATED("updated");

        private final String value;

        LifecycleEventType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public AnalyticsTracker(@NotNull String machineId, @NotNull String clientVersion) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.machineId = machineId;
        this.clientVersion = clientVersion;
    }

    /**
     * Start tracking a debug session. Fire-and-forget.
     */
    public void startSession(@Nullable String featureFile) {
        sessionStartTime = System.currentTimeMillis();

        JsonObject body = new JsonObject();
        body.addProperty("machineId", machineId);
        body.addProperty("platform", "intellij");
        body.addProperty("clientVersion", clientVersion);
        if (featureFile != null) {
            body.addProperty("featureFile", featureFile);
        }

        post("/session/start", body)
                .thenAccept(response -> {
                    try {
                        JsonObject json = GSON.fromJson(response, JsonObject.class);
                        if (json.has("sessionId")) {
                            currentSessionId = json.get("sessionId").getAsString();
                            LOG.debug("Analytics session started: " + currentSessionId);
                        }
                    } catch (Exception e) {
                        LOG.debug("Failed to parse session start response", e);
                    }
                })
                .exceptionally(e -> {
                    LOG.debug("Failed to start analytics session", e);
                    return null;
                });
    }

    /**
     * End the current debug session. Fire-and-forget.
     */
    public void endSession(@NotNull SessionOutcome outcome) {
        if (currentSessionId == null) {
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("sessionId", currentSessionId);
        body.addProperty("outcome", outcome.getValue());

        String sessionId = currentSessionId;
        currentSessionId = null;

        post("/session/end", body)
                .thenAccept(response -> LOG.debug("Analytics session ended: " + sessionId))
                .exceptionally(e -> {
                    LOG.debug("Failed to end analytics session", e);
                    return null;
                });
    }

    /**
     * Track a lifecycle event (initialized, updated). Fire-and-forget.
     */
    public void trackLifecycleEvent(@NotNull LifecycleEventType eventType, @Nullable String previousVersion) {
        JsonObject body = new JsonObject();
        body.addProperty("machineId", machineId);
        body.addProperty("platform", "intellij");
        body.addProperty("clientVersion", clientVersion);
        body.addProperty("eventType", eventType.getValue());
        if (previousVersion != null) {
            body.addProperty("previousVersion", previousVersion);
        }

        post("/lifecycle/event", body)
                .thenAccept(response -> LOG.debug("Lifecycle event tracked: " + eventType.getValue()))
                .exceptionally(e -> {
                    LOG.debug("Failed to track lifecycle event", e);
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
                    throw new RuntimeException("API error: " + response.statusCode());
                });
    }
}

