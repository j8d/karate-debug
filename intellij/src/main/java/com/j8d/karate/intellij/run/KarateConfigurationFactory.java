package com.j8d.karate.intellij.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating Karate run configurations.
 */
public class KarateConfigurationFactory extends ConfigurationFactory {
    
    public KarateConfigurationFactory(@NotNull ConfigurationType type) {
        super(type);
    }
    
    @Override
    public @NotNull @NonNls String getId() {
        return KarateConfigurationType.ID;
    }
    
    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new KarateRunConfiguration(project, this, "Karate Debug");
    }
    
    @Override
    public @Nullable Class<? extends BaseState> getOptionsClass() {
        return KarateRunConfigurationOptions.class;
    }
}

