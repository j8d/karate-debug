package com.j8d.karate.intellij.project;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    
    public static KarateProjectSettings getInstance(@NotNull Project project) {
        return project.getService(KarateProjectSettings.class);
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
}

