package com.j8d.karate.intellij.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings page for Karate Debug plugin.
 * Appears under Preferences > Tools > Karate Debug.
 */
public class KarateSettingsConfigurable implements Configurable {
    
    private final Project project;
    private JPanel mainPanel;
    
    // UI Components
    private JBTextField environmentsField;
    private ComboBox<String> defaultEnvironmentCombo;
    private ComboBox<String> logLevelCombo;
    private JBTextField javaPathField;
    private JBTextField jvmArgsField;
    private JBTextField workingDirectoryField;
    private JBTextField additionalClasspathField;
    private JBCheckBox showDetectionNotificationCheckbox;
    private JBCheckBox enableMatchDiagnosticsCheckbox;
    private JBCheckBox matchShowPassingCheckbox;
    private JBCheckBox matchShowFailingCheckbox;
    private JBCheckBox matchShowActualValuesCheckbox;
    private JBCheckBox autoDiscoverEnvironmentsCheckbox;
    private JBTextField logFilterExcludeField;
    private JBTextField logBreakpointsField;
    private JBCheckBox enableJavaDebuggingCheckbox;
    private JBCheckBox enableJsDebuggingCheckbox;
    private JBCheckBox showJdkClassesCheckbox;
    private JBCheckBox showKarateFrameworkCheckbox;
    private JBCheckBox showKarateDependenciesCheckbox;

    public KarateSettingsConfigurable(Project project) {
        this.project = project;
    }
    
    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Karate Debug";
    }
    
    @Nullable
    @Override
    public JComponent createComponent() {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
        
        // Environment settings
        environmentsField = new JBTextField(settings.environments);
        defaultEnvironmentCombo = new ComboBox<>(settings.getEnvironmentsList().toArray(new String[0]));
        defaultEnvironmentCombo.setSelectedItem(settings.defaultEnvironment);
        
        // Log level
        logLevelCombo = new ComboBox<>(KarateProjectSettings.LOG_LEVELS);
        logLevelCombo.setSelectedItem(settings.getEffectiveLogLevel());
        
        // Java settings
        javaPathField = new JBTextField(settings.javaPath);
        jvmArgsField = new JBTextField(settings.jvmArgs);
        workingDirectoryField = new JBTextField(settings.workingDirectory);
        additionalClasspathField = new JBTextField(settings.additionalClasspath);
        
        // Checkboxes
        showDetectionNotificationCheckbox = new JBCheckBox("Show notification when Karate project detected", 
            settings.showDetectionNotification);
        enableMatchDiagnosticsCheckbox = new JBCheckBox("Enable match diagnostics", 
            settings.enableMatchDiagnostics);
        matchShowPassingCheckbox = new JBCheckBox("Show passing matches", 
            settings.matchDiagnosticsShowPassing);
        matchShowFailingCheckbox = new JBCheckBox("Show failing matches", 
            settings.matchDiagnosticsShowFailing);
        matchShowActualValuesCheckbox = new JBCheckBox("Show actual values for failing matches",
            settings.matchDiagnosticsShowActualValues);
        autoDiscoverEnvironmentsCheckbox = new JBCheckBox("Auto-discover environments from karate-config.js",
            settings.autoDiscoverEnvironments);
        logFilterExcludeField = new JBTextField(settings.logFilterExclude);
        logBreakpointsField = new JBTextField(settings.logBreakpoints);

        // Polyglot debugging settings (enabling either Java or JS automatically enables polyglot mode)
        enableJavaDebuggingCheckbox = new JBCheckBox("[Experimental] Enable Java debugging",
            settings.enableJavaDebugging);
        enableJsDebuggingCheckbox = new JBCheckBox("[Experimental] Enable JavaScript debugging",
            settings.enableJsDebugging);

        // Step filtering settings (show = step into these classes, unchecked = skip them)
        showJdkClassesCheckbox = new JBCheckBox("Show JDK classes (java.*, javax.*, jdk.*, sun.*, com.sun.*)",
            settings.showJdkClasses);
        showKarateFrameworkCheckbox = new JBCheckBox("Show Karate framework classes (com.intuit.karate.*)",
            settings.showKarateFramework);
        showKarateDependenciesCheckbox = new JBCheckBox("Show Karate dependencies (jsonpath, netty, slf4j, etc.)",
            settings.showKarateDependencies);

        // Update environment combo when environments field changes
        environmentsField.addActionListener(e -> updateEnvironmentCombo());
        
        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Environments (comma-separated):"), environmentsField)
            .addLabeledComponent(new JBLabel("Default environment:"), defaultEnvironmentCombo)
            .addLabeledComponent(new JBLabel("Log level:"), logLevelCombo)
            .addSeparator()
            .addLabeledComponent(new JBLabel("Java path (empty = use project SDK):"), javaPathField)
            .addLabeledComponent(new JBLabel("JVM arguments:"), jvmArgsField)
            .addLabeledComponent(new JBLabel("Working directory (empty = project root):"), workingDirectoryField)
            .addLabeledComponent(new JBLabel("Additional classpath:"), additionalClasspathField)
            .addSeparator()
            .addComponent(showDetectionNotificationCheckbox)
            .addComponent(autoDiscoverEnvironmentsCheckbox)
            .addSeparator()
            .addComponent(enableMatchDiagnosticsCheckbox)
            .addComponent(matchShowPassingCheckbox)
            .addComponent(matchShowFailingCheckbox)
            .addComponent(matchShowActualValuesCheckbox)
            .addSeparator()
            .addLabeledComponent(new JBLabel("Log filter (comma-separated strings to hide):"), logFilterExcludeField)
            .addLabeledComponent(new JBLabel("Log breakpoints (comma-separated, pause when found in logs):"), logBreakpointsField)
            .addSeparator()
            .addComponent(enableJavaDebuggingCheckbox)
            .addComponent(enableJsDebuggingCheckbox)
            .addSeparator()
            .addComponent(new JBLabel("Step Filtering (show these classes when stepping through Java code):"))
            .addComponent(showJdkClassesCheckbox)
            .addComponent(showKarateFrameworkCheckbox)
            .addComponent(showKarateDependenciesCheckbox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        
        return mainPanel;
    }
    
    private void updateEnvironmentCombo() {
        String[] envs = environmentsField.getText().split(",");
        defaultEnvironmentCombo.removeAllItems();
        for (String env : envs) {
            String trimmed = env.trim();
            if (!trimmed.isEmpty()) {
                defaultEnvironmentCombo.addItem(trimmed);
            }
        }
    }
    
    @Override
    public boolean isModified() {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        return !environmentsField.getText().equals(settings.environments) ||
            !String.valueOf(defaultEnvironmentCombo.getSelectedItem()).equals(settings.defaultEnvironment) ||
            !String.valueOf(logLevelCombo.getSelectedItem()).equals(settings.logLevel) ||
            !javaPathField.getText().equals(settings.javaPath) ||
            !jvmArgsField.getText().equals(settings.jvmArgs) ||
            !workingDirectoryField.getText().equals(settings.workingDirectory) ||
            !additionalClasspathField.getText().equals(settings.additionalClasspath) ||
            showDetectionNotificationCheckbox.isSelected() != settings.showDetectionNotification ||
            enableMatchDiagnosticsCheckbox.isSelected() != settings.enableMatchDiagnostics ||
            matchShowPassingCheckbox.isSelected() != settings.matchDiagnosticsShowPassing ||
            matchShowFailingCheckbox.isSelected() != settings.matchDiagnosticsShowFailing ||
            matchShowActualValuesCheckbox.isSelected() != settings.matchDiagnosticsShowActualValues ||
            autoDiscoverEnvironmentsCheckbox.isSelected() != settings.autoDiscoverEnvironments ||
            !logFilterExcludeField.getText().equals(settings.logFilterExclude) ||
            !logBreakpointsField.getText().equals(settings.logBreakpoints) ||
            enableJavaDebuggingCheckbox.isSelected() != settings.enableJavaDebugging ||
            enableJsDebuggingCheckbox.isSelected() != settings.enableJsDebugging ||
            showJdkClassesCheckbox.isSelected() != settings.showJdkClasses ||
            showKarateFrameworkCheckbox.isSelected() != settings.showKarateFramework ||
            showKarateDependenciesCheckbox.isSelected() != settings.showKarateDependencies;
    }

    @Override
    public void apply() throws ConfigurationException {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        settings.environments = environmentsField.getText();
        settings.defaultEnvironment = String.valueOf(defaultEnvironmentCombo.getSelectedItem());
        settings.logLevel = String.valueOf(logLevelCombo.getSelectedItem());
        settings.javaPath = javaPathField.getText();
        settings.jvmArgs = jvmArgsField.getText();
        settings.workingDirectory = workingDirectoryField.getText();
        settings.additionalClasspath = additionalClasspathField.getText();
        settings.showDetectionNotification = showDetectionNotificationCheckbox.isSelected();
        settings.enableMatchDiagnostics = enableMatchDiagnosticsCheckbox.isSelected();
        settings.matchDiagnosticsShowPassing = matchShowPassingCheckbox.isSelected();
        settings.matchDiagnosticsShowFailing = matchShowFailingCheckbox.isSelected();
        settings.matchDiagnosticsShowActualValues = matchShowActualValuesCheckbox.isSelected();
        settings.autoDiscoverEnvironments = autoDiscoverEnvironmentsCheckbox.isSelected();
        settings.logFilterExclude = logFilterExcludeField.getText();
        settings.logBreakpoints = logBreakpointsField.getText();
        settings.enableJavaDebugging = enableJavaDebuggingCheckbox.isSelected();
        settings.enableJsDebugging = enableJsDebuggingCheckbox.isSelected();
        settings.showJdkClasses = showJdkClassesCheckbox.isSelected();
        settings.showKarateFramework = showKarateFrameworkCheckbox.isSelected();
        settings.showKarateDependencies = showKarateDependenciesCheckbox.isSelected();

        // Notify listeners (e.g., status bar widgets) that settings have changed
        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        environmentsField.setText(settings.environments);
        updateEnvironmentCombo();
        defaultEnvironmentCombo.setSelectedItem(settings.defaultEnvironment);
        logLevelCombo.setSelectedItem(settings.getEffectiveLogLevel());
        javaPathField.setText(settings.javaPath);
        jvmArgsField.setText(settings.jvmArgs);
        workingDirectoryField.setText(settings.workingDirectory);
        additionalClasspathField.setText(settings.additionalClasspath);
        showDetectionNotificationCheckbox.setSelected(settings.showDetectionNotification);
        enableMatchDiagnosticsCheckbox.setSelected(settings.enableMatchDiagnostics);
        matchShowPassingCheckbox.setSelected(settings.matchDiagnosticsShowPassing);
        matchShowFailingCheckbox.setSelected(settings.matchDiagnosticsShowFailing);
        matchShowActualValuesCheckbox.setSelected(settings.matchDiagnosticsShowActualValues);
        autoDiscoverEnvironmentsCheckbox.setSelected(settings.autoDiscoverEnvironments);
        logFilterExcludeField.setText(settings.logFilterExclude);
        logBreakpointsField.setText(settings.logBreakpoints);
        enableJavaDebuggingCheckbox.setSelected(settings.enableJavaDebugging);
        enableJsDebuggingCheckbox.setSelected(settings.enableJsDebugging);
        showJdkClassesCheckbox.setSelected(settings.showJdkClasses);
        showKarateFrameworkCheckbox.setSelected(settings.showKarateFramework);
        showKarateDependenciesCheckbox.setSelected(settings.showKarateDependencies);
    }
}

