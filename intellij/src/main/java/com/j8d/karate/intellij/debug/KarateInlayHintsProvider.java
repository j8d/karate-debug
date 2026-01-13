package com.j8d.karate.intellij.debug;

import com.intellij.codeInsight.hints.*;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.PresentationFactory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.j8d.karate.intellij.lang.KarateFileType;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * Provides inlay hints showing actual values for failed match statements.
 * These appear at the end of lines where a match has failed during debugging.
 */
public class KarateInlayHintsProvider implements InlayHintsProvider<NoSettings> {

    private static final Logger LOG = Logger.getInstance(KarateInlayHintsProvider.class);
    private static final SettingsKey<NoSettings> KEY = new SettingsKey<>("karate.match.actual.values");

    // Colors for the inlay hint background
    private static final Color HINT_BACKGROUND = new JBColor(
        new Color(255, 200, 200, 180),  // Light theme: light red
        new Color(180, 80, 80, 180)     // Dark theme: dark red
    );

    @Override
    public @NotNull NoSettings createSettings() {
        return new NoSettings();
    }

    @Override
    public @Nls @NotNull String getName() {
        return "Karate actual values";
    }

    @Override
    public @NotNull SettingsKey<NoSettings> getKey() {
        return KEY;
    }

    @Override
    public @Nullable String getPreviewText() {
        return "* match response.status == 200\n* match response.name == 'expected'";
    }

    @Override
    public @NotNull ImmediateConfigurable createConfigurable(@NotNull NoSettings settings) {
        return new ImmediateConfigurable() {
            @Override
            public @NotNull JComponent createComponent(@NotNull ChangeListener listener) {
                return new JPanel();
            }
        };
    }

    @Override
    public @Nullable InlayHintsCollector getCollectorFor(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull NoSettings settings,
            @NotNull InlayHintsSink sink) {

        LOG.info("getCollectorFor called for file: " + file.getName() + ", fileType: " + file.getFileType());

        // Only work with Karate files
        if (!KarateFileType.INSTANCE.equals(file.getFileType())) {
            LOG.info("Not a Karate file, skipping");
            return null;
        }

        Project project = file.getProject();
        KarateProjectSettings karateSettings = KarateProjectSettings.getInstance(project);

        // Check if actual values display is enabled
        if (!karateSettings.isMatchDiagnosticsShowActualValues()) {
            LOG.info("Show actual values is disabled");
            return null;
        }

        String filePath = file.getVirtualFile().getPath();
        LOG.info("Creating collector for: " + filePath);
        return new KarateInlayHintsCollector(editor, project, filePath);
    }

    /**
     * Collector that processes PSI elements and adds inlay hints for match failures.
     */
    private static class KarateInlayHintsCollector extends FactoryInlayHintsCollector {
        private final Project project;
        private final String filePath;
        private final Map<String, MatchDiagnosticsService.MatchFailureInfo> failures;
        private final java.util.Set<Integer> processedLines = new java.util.HashSet<>();

        KarateInlayHintsCollector(@NotNull Editor editor, @NotNull Project project, @NotNull String filePath) {
            super(editor);
            this.project = project;
            this.filePath = filePath;

            MatchDiagnosticsRegistry registry = MatchDiagnosticsRegistry.getInstance(project);
            this.failures = registry != null ? registry.getAllFailures() : Map.of();
            LOG.info("KarateInlayHintsCollector created for: " + filePath + ", failures count: " + failures.size());
            for (String key : failures.keySet()) {
                LOG.info("  Failure key: " + key);
            }
        }

        @Override
        public boolean collect(@NotNull PsiElement element, @NotNull Editor editor, @NotNull InlayHintsSink sink) {
            // Get the line number for this element
            int offset = element.getTextOffset();
            int lineNumber = editor.getDocument().getLineNumber(offset);

            // Check if there's a failure for this line and we haven't processed it yet
            String key = filePath + ":" + lineNumber;
            if (failures.containsKey(key) && !processedLines.contains(lineNumber)) {
                processedLines.add(lineNumber);
                MatchDiagnosticsService.MatchFailureInfo failure = failures.get(key);
                LOG.info("Adding hint for failure at line " + lineNumber + ": " + failure.actualValue);
                addHintForFailure(editor, sink, failure);
            }

            return true;
        }

        private void addHintForFailure(Editor editor, InlayHintsSink sink,
                                       MatchDiagnosticsService.MatchFailureInfo failure) {
            int lineNumber = failure.lineNumber;
            if (lineNumber < 0 || lineNumber >= editor.getDocument().getLineCount()) {
                return;
            }

            int lineEndOffset = editor.getDocument().getLineEndOffset(lineNumber);
            
            // Format the display value
            String displayActual = formatActualValue(failure);
            String hintText = "  actual: " + displayActual;

            // Create the presentation
            PresentationFactory factory = getFactory();
            InlayPresentation text = factory.smallText(hintText);
            InlayPresentation withBackground = factory.roundWithBackground(text);
            
            // Add tooltip with full value for type matchers
            String tooltip = failure.isTypeMatcher && failure.actualType != null
                ? "Full value: '" + failure.actualValue + "'"
                : "Expected: " + failure.expectedValue + ", Got: " + failure.actualValue;
            
            InlayPresentation withTooltip = factory.withTooltip(tooltip, withBackground);

            sink.addInlineElement(lineEndOffset, true, withTooltip, true);
        }

        private String formatActualValue(MatchDiagnosticsService.MatchFailureInfo failure) {
            if (failure.isTypeMatcher && failure.actualType != null) {
                return failure.actualType;
            } else if (failure.isQuoted) {
                return "'" + failure.actualValue + "'";
            } else {
                return failure.actualValue;
            }
        }
    }
}

