package com.j8d.karate.intellij.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Run configuration for Karate debug sessions.
 * Only supports Debug mode - Run mode is not available.
 * Implements RunProfileWithCompileBeforeLaunchOption to enable the "Build" before-run task.
 */
public class KarateRunConfiguration extends RunConfigurationBase<KarateRunConfigurationOptions>
        implements RunProfileWithCompileBeforeLaunchOption {

    protected KarateRunConfiguration(@NotNull Project project,
                                      @NotNull ConfigurationFactory factory,
                                      @Nullable String name) {
        super(project, factory, name);
    }


    
    @Override
    protected @NotNull KarateRunConfigurationOptions getOptions() {
        return (KarateRunConfigurationOptions) super.getOptions();
    }
    
    public String getFeatureFile() {
        return getOptions().getFeatureFile();
    }
    
    public void setFeatureFile(String featureFile) {
        getOptions().setFeatureFile(featureFile);
    }
    
    public String getScenarioName() {
        return getOptions().getScenarioName();
    }
    
    public void setScenarioName(String scenarioName) {
        getOptions().setScenarioName(scenarioName);
    }
    
    public String getKarateEnv() {
        return getOptions().getKarateEnv();
    }
    
    public void setKarateEnv(String env) {
        getOptions().setKarateEnv(env);
    }
    
    public int getScenarioLine() {
        return getOptions().getScenarioLine();
    }
    
    public void setScenarioLine(int line) {
        getOptions().setScenarioLine(line);
    }
    
    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new KarateRunConfigurationEditor(getProject());
    }
    
    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor,
                                               @NotNull ExecutionEnvironment environment) {
        // Always return state - both Run and Debug will work (Run redirects to Debug)
        return new KarateRunProfileState(this, environment);
    }

    // RunProfileWithCompileBeforeLaunchOption implementation

    @Override
    public Module @NotNull [] getModules() {
        // Return empty array to build the whole project
        return Module.EMPTY_ARRAY;
    }

    @Override
    public boolean isBuildBeforeLaunchAddedByDefault() {
        // Enable "Build" before-run task by default for Karate configurations
        return true;
    }
}

