package com.j8d.karate.intellij.run;

import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.j8d.karate.intellij.lang.KarateFile;
import com.j8d.karate.intellij.lang.KarateTokenTypes;
import com.j8d.karate.intellij.project.KarateProjectService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides gutter icons for running/debugging Karate features and scenarios.
 * Uses simple token-based detection instead of Gherkin PSI.
 */
public class KarateRunLineMarkerContributor extends RunLineMarkerContributor {

    @Override
    public @Nullable Info getInfo(@NotNull PsiElement element) {
        // Only work with Karate files
        PsiFile file = element.getContainingFile();
        if (!(file instanceof KarateFile)) {
            return null;
        }

        // Only show markers for Karate projects
        if (!isKarateProject(element)) {
            return null;
        }

        // Check element type for Feature keyword
        if (element.getNode().getElementType() == KarateTokenTypes.FEATURE_KEYWORD) {
            return createRunInfo("Run Feature", "Debug Feature");
        }

        // Check element type for Scenario keywords
        if (element.getNode().getElementType() == KarateTokenTypes.SCENARIO_KEYWORD ||
            element.getNode().getElementType() == KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD) {
            return createRunInfo("Run Scenario", "Debug Scenario");
        }

        return null;
    }

    private boolean isKarateProject(@NotNull PsiElement element) {
        return KarateProjectService.getInstance(element.getProject()).isKarateProject();
    }

    private Info createRunInfo(String runText, String debugText) {
        AnAction[] actions = ExecutorAction.getActions(0);
        return new Info(
            AllIcons.RunConfigurations.TestState.Run,
            actions,
            element -> runText + " / " + debugText
        );
    }
}

