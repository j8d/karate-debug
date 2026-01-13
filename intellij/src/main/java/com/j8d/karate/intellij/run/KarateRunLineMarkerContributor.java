package com.j8d.karate.intellij.run;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.j8d.karate.intellij.lang.KarateFile;
import com.j8d.karate.intellij.lang.KarateTokenTypes;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides gutter icons for debugging Karate features and scenarios.
 * Only shows Debug option (no Run) for consistency with VS Code extension.
 * Uses simple token-based detection instead of Gherkin PSI.
 */
public class KarateRunLineMarkerContributor extends RunLineMarkerContributor {

    private static final Logger LOG = Logger.getInstance(KarateRunLineMarkerContributor.class);
    private static final Pattern SCENARIO_PATTERN = Pattern.compile("^\\s*Scenario:\\s*(.+)$");
    private static final Pattern SCENARIO_OUTLINE_PATTERN = Pattern.compile("^\\s*Scenario Outline:\\s*(.+)$");

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        // Only work with Karate files
        PsiFile file = element.getContainingFile();
        if (!(file instanceof KarateFile)) {
            return null;
        }

        // Only show markers for Karate projects
        KarateProjectService projectService = KarateProjectService.getInstance(element.getProject());
        if (!projectService.isKarateProject()) {
            return null;
        }

        // Check element type for Feature keyword
        if (element.getNode().getElementType() == KarateTokenTypes.FEATURE_KEYWORD) {
            return createDebugInfo(element, "Debug Feature", null, 0);
        }

        // Check element type for Scenario keywords
        if (element.getNode().getElementType() == KarateTokenTypes.SCENARIO_KEYWORD ||
            element.getNode().getElementType() == KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD) {
            // Get scenario name and line number
            Document document = PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
            if (document != null) {
                int lineNumber = document.getLineNumber(element.getTextOffset()) + 1; // 1-based
                String lineText = getLineText(document, lineNumber - 1);
                String scenarioName = extractScenarioName(lineText);
                return createDebugInfo(element, "Debug Scenario", scenarioName, lineNumber);
            }
            return createDebugInfo(element, "Debug Scenario", null, 0);
        }

        return null;
    }

    private String getLineText(Document document, int lineNumber) {
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        return document.getText().substring(lineStart, lineEnd);
    }

    private String extractScenarioName(String lineText) {
        Matcher scenarioMatcher = SCENARIO_PATTERN.matcher(lineText);
        if (scenarioMatcher.matches()) {
            return scenarioMatcher.group(1).trim();
        }
        Matcher outlineMatcher = SCENARIO_OUTLINE_PATTERN.matcher(lineText);
        if (outlineMatcher.matches()) {
            return outlineMatcher.group(1).trim();
        }
        return null;
    }

    private Info createDebugInfo(PsiElement element, String debugText, @Nullable String scenarioName, int scenarioLine) {
        AnAction debugAction = new KarateDebugGutterAction(element, scenarioName, scenarioLine);

        return new Info(
            AllIcons.Actions.StartDebugger,
            new AnAction[]{debugAction},
            e -> debugText
        );
    }

    /**
     * Custom action that directly creates and runs a Karate debug configuration.
     */
    private static class KarateDebugGutterAction extends AnAction {
        private final PsiElement element;
        private final String scenarioName;
        private final int scenarioLine;

        KarateDebugGutterAction(PsiElement element, @Nullable String scenarioName, int scenarioLine) {
            super("Debug");
            this.element = element;
            this.scenarioName = scenarioName;
            this.scenarioLine = scenarioLine;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = element.getProject();
            PsiFile psiFile = element.getContainingFile();
            if (psiFile == null || psiFile.getVirtualFile() == null) {
                return;
            }

            String featurePath = psiFile.getVirtualFile().getPath();
            String configName = scenarioName != null ?
                "Karate: " + scenarioName :
                "Karate: " + psiFile.getVirtualFile().getNameWithoutExtension();

            // Create run configuration
            RunManager runManager = RunManager.getInstance(project);
            KarateConfigurationType configType = KarateConfigurationType.getInstance();
            RunnerAndConfigurationSettings settings = runManager.createConfiguration(
                configName, configType.getConfigurationFactories()[0]);

            KarateRunConfiguration config = (KarateRunConfiguration) settings.getConfiguration();
            config.setFeatureFile(featurePath);
            config.setScenarioName(scenarioName);
            config.setScenarioLine(scenarioLine);

            // Add as temporary configuration and run
            runManager.setTemporaryConfiguration(settings);
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
        }
    }
}

