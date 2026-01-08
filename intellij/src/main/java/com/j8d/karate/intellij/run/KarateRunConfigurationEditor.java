package com.j8d.karate.intellij.run;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Settings editor for Karate run configurations.
 */
public class KarateRunConfigurationEditor extends SettingsEditor<KarateRunConfiguration> {
    
    private final JPanel panel;
    private final TextFieldWithBrowseButton featureFileField;
    private final JBTextField scenarioNameField;
    private final JBTextField karateEnvField;
    
    public KarateRunConfigurationEditor(Project project) {
        featureFileField = new TextFieldWithBrowseButton();
        featureFileField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("feature")
                .withTitle("Select Feature File")
                .withDescription("Select the Karate feature file to run")
        );
        
        scenarioNameField = new JBTextField();
        karateEnvField = new JBTextField();
        
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Feature file:", featureFileField)
            .addLabeledComponent("Scenario (optional):", scenarioNameField)
            .addLabeledComponent("Environment:", karateEnvField)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }
    
    @Override
    protected void resetEditorFrom(@NotNull KarateRunConfiguration config) {
        featureFileField.setText(config.getFeatureFile());
        scenarioNameField.setText(config.getScenarioName());
        karateEnvField.setText(config.getKarateEnv());
    }
    
    @Override
    protected void applyEditorTo(@NotNull KarateRunConfiguration config) {
        config.setFeatureFile(featureFileField.getText());
        config.setScenarioName(scenarioNameField.getText());
        config.setKarateEnv(karateEnvField.getText());
    }
    
    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}

