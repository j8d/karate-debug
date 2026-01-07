package com.j8d.karate.intellij.project;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    
    // Default Karate environment to use
    public String defaultEnvironment = "";
    
    // Custom Java executable path (empty = use project SDK)
    public String javaPath = "";
    
    // Custom JVM arguments for running Karate
    public String jvmArgs = "";
    
    // Whether to show balloon notification on project detection
    public boolean showDetectionNotification = true;
    
    // Whether to enable match diagnostics (inline hints for failed matches)
    public boolean enableMatchDiagnostics = true;
    
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
     * Returns the user-configured default, or "default" if not set.
     */
    @NotNull
    public String getEffectiveEnvironment() {
        return defaultEnvironment.isEmpty() ? "default" : defaultEnvironment;
    }
    
    /**
     * Check if a custom Java path is configured.
     */
    public boolean hasCustomJavaPath() {
        return javaPath != null && !javaPath.isEmpty();
    }
}

