package com.j8d.karate.intellij.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.j8d.karate.intellij.lang.KarateFile;
import org.jetbrains.annotations.NotNull;

/**
 * Action to run a Karate feature file.
 */
public class RunFeatureAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || !(file instanceof KarateFile)) {
            return;
        }

        // TODO: Create and run a Karate run configuration
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(file instanceof KarateFile);
    }
}

