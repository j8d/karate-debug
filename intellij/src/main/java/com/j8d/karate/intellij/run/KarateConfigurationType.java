package com.j8d.karate.intellij.run;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Configuration type for Karate debug sessions.
 */
public class KarateConfigurationType implements ConfigurationType {

    public static final String ID = "KarateDebugConfiguration";

    public static KarateConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(KarateConfigurationType.class);
    }
    
    @Override
    public @NotNull @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Karate Debug";
    }
    
    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) String getConfigurationTypeDescription() {
        return "Debug Karate feature files";
    }
    
    @Override
    public Icon getIcon() {
        // TODO: Use custom Karate icon
        return AllIcons.Actions.StartDebugger;
    }
    
    @Override
    public @NotNull @NonNls String getId() {
        return ID;
    }
    
    @Override
    public ConfigurationFactory[] getConfigurationFactories() {
        return new ConfigurationFactory[]{new KarateConfigurationFactory(this)};
    }
}

