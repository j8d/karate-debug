package com.j8d.karate.intellij.run;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.j8d.karate.intellij.lang.KarateFile;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

        // Check if we're on a specific scenario
        String scenarioName = findScenarioName(element, psiFile);
        if (scenarioName != null) {
            configuration.setScenarioName(scenarioName);
            configuration.setName("Karate: " + scenarioName);
        } else {
            configuration.setName("Karate: " + file.getNameWithoutExtension());
        }

        sourceElement.set(element);
        return true;
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

        // Check if the scenario name matches
        String scenarioName = findScenarioName(element, psiFile);
        String configScenarioName = configuration.getScenarioName();

        if (scenarioName == null && (configScenarioName == null || configScenarioName.isEmpty())) {
            return true;
        }
        if (scenarioName != null && scenarioName.equals(configScenarioName)) {
            return true;
        }

        return false;
    }

    /**
     * Find the scenario name by looking at the text content around the element.
     * Walks backwards through lines to find the nearest Scenario: or Scenario Outline: line.
     */
    @Nullable
    private String findScenarioName(PsiElement element, PsiFile psiFile) {
        Document document = PsiDocumentManager.getInstance(element.getProject())
            .getDocument(psiFile);
        if (document == null) {
            return null;
        }

        int offset = element.getTextOffset();
        int lineNumber = document.getLineNumber(offset);

        // Walk backwards from current line to find scenario
        for (int line = lineNumber; line >= 0; line--) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText().substring(lineStart, lineEnd);

            Matcher scenarioMatcher = SCENARIO_PATTERN.matcher(lineText);
            if (scenarioMatcher.matches()) {
                return scenarioMatcher.group(1).trim();
            }

            Matcher outlineMatcher = SCENARIO_OUTLINE_PATTERN.matcher(lineText);
            if (outlineMatcher.matches()) {
                return outlineMatcher.group(1).trim();
            }

            // Stop if we hit a Feature line (we're not in a scenario)
            if (lineText.trim().startsWith("Feature:")) {
                return null;
            }
        }

        return null;
    }
}

