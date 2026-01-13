package com.j8d.karate.intellij.debug;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project-level service to store match diagnostic failures.
 * Allows quick fix intentions to access failure information.
 */
@Service(Service.Level.PROJECT)
public final class MatchDiagnosticsRegistry {

    private static final Logger LOG = Logger.getInstance(MatchDiagnosticsRegistry.class);
    private final Map<String, MatchDiagnosticsService.MatchFailureInfo> failures = new ConcurrentHashMap<>();

    @Nullable
    public static MatchDiagnosticsRegistry getInstance(@NotNull Project project) {
        return project.getService(MatchDiagnosticsRegistry.class);
    }

    /**
     * Store a match failure.
     * @param key Format: "filePath:lineNumber"
     */
    public void addFailure(@NotNull String key, @NotNull MatchDiagnosticsService.MatchFailureInfo failure) {
        LOG.info("addFailure: key=" + key + ", actual=" + failure.actualValue);
        failures.put(key, failure);
        LOG.info("addFailure: total failures now: " + failures.size());
    }

    /**
     * Get a match failure by key.
     */
    @Nullable
    public MatchDiagnosticsService.MatchFailureInfo getFailure(@NotNull String key) {
        return failures.get(key);
    }

    /**
     * Check if there's a failure at the given key.
     */
    public boolean hasFailureAt(@NotNull String key) {
        return failures.containsKey(key);
    }

    /**
     * Clear a specific failure.
     */
    public void clearFailure(@NotNull String key) {
        failures.remove(key);
    }

    /**
     * Clear all failures for a file.
     */
    public void clearFailuresForFile(@NotNull String filePath) {
        failures.entrySet().removeIf(e -> e.getKey().startsWith(filePath + ":"));
    }

    /**
     * Clear all failures.
     */
    public void clearAll() {
        failures.clear();
    }

    /**
     * Get all failures (read-only view).
     */
    @NotNull
    public Map<String, MatchDiagnosticsService.MatchFailureInfo> getAllFailures() {
        return Map.copyOf(failures);
    }
}

