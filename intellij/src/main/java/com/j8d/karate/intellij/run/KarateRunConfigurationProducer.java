package com.j8d.karate.intellij.run;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.BeforeRunTaskProvider;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.j8d.karate.intellij.lang.KarateFile;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces Karate run configurations from context (e.g., right-click on file or gutter icon).
 * Uses simple text-based parsing instead of Gherkin PSI.
 */
public class KarateRunConfigurationProducer extends LazyRunConfigurationProducer<KarateRunConfiguration> {

    private static final Pattern SCENARIO_PATTERN = Pattern.compile("^\\s*Scenario:\\s*(.+)$");
    private static final Pattern SCENARIO_OUTLINE_PATTERN = Pattern.compile("^\\s*Scenario Outline:\\s*(.+)$");

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return KarateConfigurationType.getInstance().getConfigurationFactories()[0];
    }

    @Override
    protected boolean setupConfigurationFromContext(@NotNull KarateRunConfiguration configuration,
                                                     @NotNull ConfigurationContext context,
                                                     @NotNull Ref<PsiElement> sourceElement) {
        PsiElement element = context.getPsiLocation();
        if (element == null) {
            return false;
        }

        // Check if this is a Karate project
        if (!KarateProjectService.getInstance(context.getProject()).isKarateProject()) {
            return false;
        }

        // Get the containing file
        PsiFile psiFile = element.getContainingFile();
        if (!(psiFile instanceof KarateFile)) {
            return false;
        }

        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return false;
        }

        // Set up the configuration
        configuration.setFeatureFile(file.getPath());

        // Check if we're on a specific scenario - get both name and line number
        ScenarioInfo scenarioInfo = findScenarioInfo(element, psiFile);
        if (scenarioInfo != null) {
            configuration.setScenarioName(scenarioInfo.name);
            configuration.setScenarioLine(scenarioInfo.lineNumber);
            configuration.setName("Karate: " + scenarioInfo.name);
        } else {
            configuration.setScenarioLine(0); // 0 means run entire feature
            configuration.setName("Karate: " + file.getNameWithoutExtension());
        }

        // Add Build before-run task
        addMakeBeforeRunTask(context.getProject(), configuration);

        sourceElement.set(element);
        return true;
    }

    /**
     * Add the "Build" (Make) task as a default before-run task.
     * This ensures feature files are copied to the classpath before debugging.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addMakeBeforeRunTask(@NotNull Project project, @NotNull RunConfigurationBase<?> config) {
        // Check if already has a Make task
        for (BeforeRunTask<?> existingTask : config.getBeforeRunTasks()) {
            String taskId = existingTask.getProviderId().toString();
            if ("Make".equals(taskId) || taskId.contains("CompileStepBeforeRun")) {
                return; // Already has Make task
            }
        }

        // Find the Make before run task provider and add it
        for (BeforeRunTaskProvider provider : BeforeRunTaskProvider.EP_NAME.getExtensions(project)) {
            String providerId = provider.getId().toString();
            if ("Make".equals(providerId) || providerId.contains("CompileStepBeforeRun")) {
                BeforeRunTask task = provider.createTask(config);
                if (task != null) {
                    task.setEnabled(true);
                    List<BeforeRunTask<?>> tasks = new ArrayList<>(config.getBeforeRunTasks());
                    tasks.add(task);
                    config.setBeforeRunTasks(tasks);
                    return;
                }
            }
        }
    }

    /**
     * Holds scenario name and line number.
     */
    private static class ScenarioInfo {
        final String name;
        final int lineNumber; // 1-based line number

        ScenarioInfo(String name, int lineNumber) {
            this.name = name;
            this.lineNumber = lineNumber;
        }
    }

    @Override
    public boolean isConfigurationFromContext(@NotNull KarateRunConfiguration configuration,
                                               @NotNull ConfigurationContext context) {
        PsiElement element = context.getPsiLocation();
        if (element == null) {
            return false;
        }

        PsiFile psiFile = element.getContainingFile();
        if (!(psiFile instanceof KarateFile)) {
            return false;
        }

        VirtualFile file = psiFile.getVirtualFile();
        if (file == null) {
            return false;
        }

        // Check if the feature file matches
        if (!file.getPath().equals(configuration.getFeatureFile())) {
            return false;
        }

        // Check if the scenario line matches
        ScenarioInfo scenarioInfo = findScenarioInfo(element, psiFile);
        int configLine = configuration.getScenarioLine();

        if (scenarioInfo == null && configLine <= 0) {
            return true; // Both are "run entire feature"
        }
        if (scenarioInfo != null && scenarioInfo.lineNumber == configLine) {
            return true;
        }

        return false;
    }

    /**
     * Find the scenario info by looking at the text content around the element.
     * First checks the current line, then walks backwards to find the nearest Scenario line.
     * If the element IS on a Scenario line, returns that scenario's info.
     * If the element IS on a Feature line, returns null (run whole feature).
     */
    @Nullable
    private ScenarioInfo findScenarioInfo(PsiElement element, PsiFile psiFile) {
        Document document = PsiDocumentManager.getInstance(element.getProject())
            .getDocument(psiFile);
        if (document == null) {
            return null;
        }

        int offset = element.getTextOffset();
        int lineNumber = document.getLineNumber(offset);

        // First check the current line - if we're on a Feature line, return null (run whole feature)
        String currentLineText = getLineText(document, lineNumber);
        if (currentLineText.trim().startsWith("Feature:")) {
            return null;
        }

        // Walk backwards from current line to find scenario (including current line)
        for (int line = lineNumber; line >= 0; line--) {
            String lineText = getLineText(document, line);

            Matcher scenarioMatcher = SCENARIO_PATTERN.matcher(lineText);
            if (scenarioMatcher.matches()) {
                // Return 1-based line number (line is 0-based from document)
                return new ScenarioInfo(scenarioMatcher.group(1).trim(), line + 1);
            }

            Matcher outlineMatcher = SCENARIO_OUTLINE_PATTERN.matcher(lineText);
            if (outlineMatcher.matches()) {
                // Return 1-based line number
                return new ScenarioInfo(outlineMatcher.group(1).trim(), line + 1);
            }

            // Stop if we hit a Feature line (we're between Feature and first Scenario)
            if (lineText.trim().startsWith("Feature:")) {
                return null;
            }
        }

        return null;
    }

    private String getLineText(Document document, int lineNumber) {
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        return document.getText().substring(lineStart, lineEnd);
    }
}

