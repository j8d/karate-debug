package com.j8d.karate.intellij.project;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent project-level settings for Karate Debug.
 * Stores user preferences like default environment, Java path, etc.
 */
@State(
    name = "KarateDebugSettings",
    storages = @Storage("karateDebug.xml")
)
@Service(Service.Level.PROJECT)
public final class KarateProjectSettings implements PersistentStateComponent<KarateProjectSettings> {

    // Available environments (comma-separated for XML serialization)
    public String environments = "dev,qa,stage";

    // Default/current Karate environment to use
    public String defaultEnvironment = "dev";

    // Log level for debug output
    public String logLevel = "info";

    // Custom Java executable path (empty = use project SDK)
    public String javaPath = "";

    // Custom JVM arguments for running Karate
    public String jvmArgs = "";

    // Whether to show balloon notification on project detection
    public boolean showDetectionNotification = true;

    // Whether to enable match diagnostics (inline hints for failed matches)
    public boolean enableMatchDiagnostics = true;

    // Match diagnostics: show passing matches
    public boolean matchDiagnosticsShowPassing = true;

    // Match diagnostics: show failing matches
    public boolean matchDiagnosticsShowFailing = true;

    // Match diagnostics: show actual values for failing matches
    public boolean matchDiagnosticsShowActualValues = true;

    // Whether to auto-discover environments from karate-config.js
    public boolean autoDiscoverEnvironments = true;

    // Custom classpath additions (semicolon-separated)
    public String additionalClasspath = "";

    // Working directory for running tests (empty = project root)
    public String workingDirectory = "";

    // Log filter: comma-separated list of strings to exclude from log output
    public String logFilterExclude = "";

    // Log breakpoints: comma-separated list of strings that trigger a pause when found in logs
    public String logBreakpoints = "";

    // [Experimental] Port for JavaScript debugger (Chrome DevTools Protocol)
    // When set to a value > 0, enables debugging of embedded JavaScript in Karate tests
    public int jsDebugPort = 0;

    // Listeners for settings changes (not serialized)
    @Transient
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public static KarateProjectSettings getInstance(@NotNull Project project) {
        return project.getService(KarateProjectSettings.class);
    }

    /**
     * Add a listener to be notified when settings change.
     * The listener is called on the EDT.
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Remove a settings change listener.
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /**
     * Notify all listeners that settings have changed.
     * Should be called after modifying settings programmatically.
     */
    public void fireSettingsChanged() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Runnable listener : changeListeners) {
                listener.run();
            }
        });
    }
    
    @Nullable
    @Override
    public KarateProjectSettings getState() {
        return this;
    }
    
    @Override
    public void loadState(@NotNull KarateProjectSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
    
    /**
     * Get the effective environment to use for running tests.
     * Returns the user-configured default, or "dev" if not set.
     */
    @NotNull
    public String getEffectiveEnvironment() {
        return defaultEnvironment.isEmpty() ? "dev" : defaultEnvironment;
    }

    /**
     * Get the list of available environments.
     */
    @NotNull
    public List<String> getEnvironmentsList() {
        if (environments == null || environments.isEmpty()) {
            return Arrays.asList("dev", "qa", "stage");
        }
        return Arrays.asList(environments.split(","));
    }

    /**
     * Set the list of available environments.
     */
    public void setEnvironmentsList(@NotNull List<String> envList) {
        this.environments = String.join(",", envList);
    }

    /**
     * Add an environment to the list if it doesn't exist.
     */
    public void addEnvironment(@NotNull String env) {
        List<String> current = new ArrayList<>(getEnvironmentsList());
        if (!current.contains(env)) {
            current.add(env);
            setEnvironmentsList(current);
        }
    }

    /**
     * Get the effective log level.
     */
    @NotNull
    public String getEffectiveLogLevel() {
        return logLevel == null || logLevel.isEmpty() ? "info" : logLevel;
    }

    /**
     * Check if a custom Java path is configured.
     */
    public boolean hasCustomJavaPath() {
        return javaPath != null && !javaPath.isEmpty();
    }

    /**
     * Valid log levels.
     */
    public static final String[] LOG_LEVELS = {"error", "warn", "info", "debug", "trace"};

    // ========== Match Diagnostics Getters ==========

    /**
     * Check if match diagnostics are enabled.
     */
    public boolean isMatchDiagnosticsEnabled() {
        return enableMatchDiagnostics;
    }

    /**
     * Check if passing matches should be highlighted.
     */
    public boolean isMatchDiagnosticsShowPassing() {
        return matchDiagnosticsShowPassing;
    }

    /**
     * Check if failing matches should be highlighted.
     */
    public boolean isMatchDiagnosticsShowFailing() {
        return matchDiagnosticsShowFailing;
    }

    /**
     * Check if actual values should be shown for failing matches.
     */
    public boolean isMatchDiagnosticsShowActualValues() {
        return matchDiagnosticsShowActualValues;
    }

    // ========== Log Filter Getters ==========

    /**
     * Get the list of log filter exclude patterns.
     * Returns an empty list if no patterns are configured.
     */
    @NotNull
    public List<String> getLogFilterExcludePatterns() {
        if (logFilterExclude == null || logFilterExclude.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(logFilterExclude.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * Check if a log message should be filtered out (case-insensitive).
     */
    public boolean shouldFilterLog(@NotNull String message) {
        List<String> patterns = getLogFilterExcludePatterns();
        if (patterns.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return patterns.stream().anyMatch(p -> lowerMessage.contains(p.toLowerCase()));
    }

    /**
     * Get the list of log breakpoint patterns.
     * Returns an empty list if no patterns are configured.
     */
    @NotNull
    public List<String> getLogBreakpointPatterns() {
        if (logBreakpoints == null || logBreakpoints.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(logBreakpoints.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}

