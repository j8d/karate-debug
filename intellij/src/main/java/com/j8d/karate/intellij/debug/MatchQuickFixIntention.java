package com.j8d.karate.intellij.debug;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import com.j8d.karate.intellij.lang.KarateFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nls;

/**
 * Quick fix intention action for failed match expressions.
 * Allows replacing expected value with actual value.
 */
public class MatchQuickFixIntention extends PsiElementBaseIntentionAction implements IntentionAction {

    // Cache the current failure info for getText()
    private MatchDiagnosticsService.MatchFailureInfo cachedFailure;

    @Override
    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    public String getText() {
        if (cachedFailure != null && cachedFailure.actualValue != null && !cachedFailure.actualValue.isEmpty()) {
            String expected = cachedFailure.expectedValue;
            String replacement;

            if (cachedFailure.isTypeMatcher && cachedFailure.actualType != null) {
                // For type matchers, show the type matcher that will be used
                replacement = cachedFailure.actualType;
            } else {
                replacement = cachedFailure.actualValue;
            }

            if (cachedFailure.isQuoted) {
                return "Replace '" + expected + "' with '" + replacement + "'";
            } else {
                return "Replace " + expected + " with " + replacement;
            }
        }
        return "Replace expected value with actual value";
    }

    @Override
    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    public String getFamilyName() {
        return "Karate Match";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        cachedFailure = null;

        // Check if this is a Karate file
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) {
            return false;
        }
        if (!KarateFileType.INSTANCE.equals(file.getFileType())) {
            return false;
        }

        // Check if there's a match failure on this line
        int lineNumber = editor.getDocument().getLineNumber(element.getTextOffset());
        String key = file.getVirtualFile().getPath() + ":" + lineNumber;

        // We need to access the MatchDiagnosticsService to check for failures
        MatchDiagnosticsRegistry registry = MatchDiagnosticsRegistry.getInstance(project);
        if (registry != null && registry.hasFailureAt(key)) {
            cachedFailure = registry.getFailure(key);
            // Only show quick fix if we have a valid expected range to replace
            return cachedFailure != null && cachedFailure.expectedRange != null;
        }
        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element)
            throws IncorrectOperationException {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return;

        int lineNumber = editor.getDocument().getLineNumber(element.getTextOffset());
        String key = file.getVirtualFile().getPath() + ":" + lineNumber;

        MatchDiagnosticsRegistry registry = MatchDiagnosticsRegistry.getInstance(project);
        if (registry == null) return;

        MatchDiagnosticsService.MatchFailureInfo failure = registry.getFailure(key);
        if (failure == null || failure.expectedRange == null) return;

        // Replace expected value with actual value (or correct type matcher)
        String replacement;
        if (failure.isTypeMatcher && failure.actualType != null) {
            // For type matchers, replace with the correct type matcher
            replacement = "'" + failure.actualType + "'";
        } else {
            replacement = failure.isQuoted
                ? "'" + failure.actualValue + "'"
                : failure.actualValue;
        }

        editor.getDocument().replaceString(
            failure.expectedRange.getStartOffset(),
            failure.expectedRange.getEndOffset(),
            replacement
        );

        // Clear the failure after fix
        registry.clearFailure(key);
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}

