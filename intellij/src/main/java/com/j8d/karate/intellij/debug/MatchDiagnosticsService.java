package com.j8d.karate.intellij.debug;

import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.j8d.karate.intellij.lang.KarateFileType;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides real-time match expression diagnostics during debugging.
 * Shows green highlights for passing matches and red for failing.
 */
public class MatchDiagnosticsService implements XDebugSessionListener, Disposable {

    private static final Logger LOG = Logger.getInstance(MatchDiagnosticsService.class);

    // Regex to match Karate match statements
    private static final Pattern MATCH_REGEX = Pattern.compile(
        "^\\s*(\\*|Given|When|Then|And|But)\\s+match\\s+(.+)$"
    );

    private final Project project;
    private final KarateDapClient dapClient;
    private final MatchDiagnosticsRegistry registry;
    private final Map<String, java.util.List<RangeHighlighter>> highlightersByFile = new ConcurrentHashMap<>();
    private final Map<Editor, EditorMouseListener> mouseListeners = new ConcurrentHashMap<>();

    private boolean isPaused = false;

    // Document change listener for real-time updates
    private com.intellij.openapi.editor.event.DocumentListener documentListener;
    private Editor currentEditor;
    private javax.swing.Timer debounceTimer;
    private static final int DEBOUNCE_DELAY_MS = 300;

    // Colors for highlights
    private static final Color PASS_COLOR = new JBColor(
        new Color(0, 128, 0, 40),  // Light theme: green
        new Color(0, 128, 0, 60)   // Dark theme: green
    );
    private static final Color FAIL_COLOR = new JBColor(
        new Color(255, 0, 0, 40),  // Light theme: red
        new Color(255, 0, 0, 60)   // Dark theme: red
    );

    public MatchDiagnosticsService(Project project, KarateDapClient dapClient) {
        this.project = project;
        this.dapClient = dapClient;
        this.registry = MatchDiagnosticsRegistry.getInstance(project);
    }

    /**
     * Information about a failed match for quick fixes.
     */
    public static class MatchFailureInfo {
        public final int lineNumber;
        public final String expectedValue;
        public final String actualValue;
        public final TextRange expectedRange;  // Keep for initial range info
        public com.intellij.openapi.editor.RangeMarker rangeMarker;  // Tracks document changes
        public final boolean isQuoted;
        public final boolean isTypeMatcher;
        public final String actualType;
        public final boolean isErrorMessage;  // True if this is an error message, not a value mismatch

        public MatchFailureInfo(int lineNumber, String expectedValue, String actualValue,
                               TextRange expectedRange, boolean isQuoted,
                               boolean isTypeMatcher, String actualType) {
            this(lineNumber, expectedValue, actualValue, expectedRange, isQuoted, isTypeMatcher, actualType, false);
        }

        public MatchFailureInfo(int lineNumber, String expectedValue, String actualValue,
                               TextRange expectedRange, boolean isQuoted,
                               boolean isTypeMatcher, String actualType, boolean isErrorMessage) {
            this.lineNumber = lineNumber;
            this.expectedValue = expectedValue;
            this.actualValue = actualValue;
            this.expectedRange = expectedRange;
            this.isQuoted = isQuoted;
            this.isTypeMatcher = isTypeMatcher;
            this.actualType = actualType;
            this.isErrorMessage = isErrorMessage;
        }

        /**
         * Create a RangeMarker from the expectedRange for a given document.
         * RangeMarkers automatically adjust when the document changes.
         */
        public void createRangeMarker(Document document) {
            if (expectedRange != null && rangeMarker == null) {
                rangeMarker = document.createRangeMarker(
                    expectedRange.getStartOffset(),
                    expectedRange.getEndOffset()
                );
                rangeMarker.setGreedyToLeft(false);
                rangeMarker.setGreedyToRight(false);
            }
        }

        /**
         * Get the current valid range (from RangeMarker if available, else from TextRange).
         */
        public TextRange getCurrentRange() {
            if (rangeMarker != null && rangeMarker.isValid()) {
                return new TextRange(rangeMarker.getStartOffset(), rangeMarker.getEndOffset());
            }
            return expectedRange;
        }

        /**
         * Dispose the range marker when no longer needed.
         */
        public void dispose() {
            if (rangeMarker != null) {
                rangeMarker.dispose();
                rangeMarker = null;
            }
        }
    }

    // XDebugSessionListener implementation

    @Override
    public void sessionPaused() {
        LOG.info("sessionPaused called");
        isPaused = true;
        startDocumentListener();
        evaluateMatchStatements();
    }

    @Override
    public void sessionResumed() {
        LOG.info("sessionResumed called");
        isPaused = false;
        stopDocumentListener();
        clearHighlights();
    }

    @Override
    public void sessionStopped() {
        LOG.info("sessionStopped called");
        isPaused = false;
        stopDocumentListener();
        clearHighlights();
        if (registry != null) {
            registry.clearAll();
        }
    }

    @Override
    public void stackFrameChanged() {
        LOG.info("stackFrameChanged called, isPaused=" + isPaused);
        if (isPaused) {
            evaluateMatchStatements();
        }
    }

    /**
     * Start listening for document changes to update highlights in real-time.
     */
    private void startDocumentListener() {
        if (documentListener != null) return;

        // Get the current editor
        currentEditor = ReadAction.compute(() -> {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            return fem.getSelectedTextEditor();
        });

        if (currentEditor == null) return;

        documentListener = new com.intellij.openapi.editor.event.DocumentListener() {
            @Override
            public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                if (!isPaused || !isEnabled()) return;

                // Check if the document is a Karate file
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file == null || !file.getName().endsWith(".feature")) return;

                debounceEvaluate();
            }
        };

        // Use Disposable-aware listener to avoid deprecated API
        currentEditor.getDocument().addDocumentListener(documentListener, this);
        LOG.info("Document listener started for real-time match updates");
    }

    /**
     * Stop listening for document changes.
     */
    private void stopDocumentListener() {
        // Listener is automatically removed when this Disposable is disposed
        documentListener = null;
        LOG.info("Document listener stopped");
        if (debounceTimer != null) {
            debounceTimer.stop();
            debounceTimer = null;
        }
        currentEditor = null;
    }

    /**
     * Debounce the evaluation to avoid excessive re-evaluations while typing.
     */
    private void debounceEvaluate() {
        if (debounceTimer != null) {
            debounceTimer.stop();
        }

        debounceTimer = new javax.swing.Timer(DEBOUNCE_DELAY_MS, e -> {
            debounceTimer = null;
            if (isPaused && isEnabled()) {
                evaluateMatchStatements();
            }
        });
        debounceTimer.setRepeats(false);
        debounceTimer.start();
    }

    public boolean isEnabled() {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
        return settings.isMatchDiagnosticsEnabled();
    }

    public Map<String, MatchFailureInfo> getMatchFailures() {
        return registry != null ? registry.getAllFailures() : Collections.emptyMap();
    }

    /**
     * Get a match failure info by key (format: "filePath:lineNumber")
     */
    public MatchFailureInfo getMatchFailure(String key) {
        return registry != null ? registry.getFailure(key) : null;
    }

    @Override
    public void dispose() {
        stopDocumentListener();
        clearHighlights();
        clearAllMouseListeners();
        if (registry != null) {
            registry.clearAll();
        }
    }

    /**
     * Remove all installed mouse listeners.
     */
    private void clearAllMouseListeners() {
        for (Map.Entry<Editor, EditorMouseListener> entry : mouseListeners.entrySet()) {
            try {
                entry.getKey().removeEditorMouseListener(entry.getValue());
            } catch (Exception e) {
                LOG.debug("Error removing mouse listener", e);
            }
        }
        mouseListeners.clear();
    }

    // Regex to identify scenario/scenario outline start lines
    private static final Pattern SCENARIO_START_REGEX = Pattern.compile(
        "^\\s*(Scenario|Scenario Outline):\\s*.*$"
    );

    // Regex to identify Background start lines
    private static final Pattern BACKGROUND_START_REGEX = Pattern.compile(
        "^\\s*Background:\\s*$"
    );

    /**
     * Represents the line range of a scenario (start inclusive, end inclusive).
     */
    private static class ScenarioRange {
        final int startLine;  // 0-based, inclusive
        final int endLine;    // 0-based, inclusive

        ScenarioRange(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }

        boolean contains(int line) {
            return line >= startLine && line <= endLine;
        }
    }

    /**
     * Find all scenario ranges in the document.
     * A scenario starts at "Scenario:" or "Scenario Outline:" and ends
     * when the next scenario starts or the file ends.
     * Background sections are not treated as scenarios.
     */
    private List<ScenarioRange> findScenarioRanges(Document document) {
        List<ScenarioRange> ranges = new ArrayList<>();
        int lineCount = document.getLineCount();
        int currentScenarioStart = -1;

        for (int lineNum = 0; lineNum < lineCount; lineNum++) {
            int startOffset = document.getLineStartOffset(lineNum);
            int endOffset = document.getLineEndOffset(lineNum);
            String lineText = document.getText(new TextRange(startOffset, endOffset));

            if (SCENARIO_START_REGEX.matcher(lineText).matches()) {
                // Close previous scenario if any
                if (currentScenarioStart >= 0) {
                    ranges.add(new ScenarioRange(currentScenarioStart, lineNum - 1));
                }
                currentScenarioStart = lineNum;
            }
        }

        // Close the last scenario
        if (currentScenarioStart >= 0) {
            ranges.add(new ScenarioRange(currentScenarioStart, lineCount - 1));
        }

        return ranges;
    }

    /**
     * Find the scenario range that contains the given line.
     */
    private ScenarioRange findScenarioForLine(List<ScenarioRange> ranges, int line) {
        for (ScenarioRange range : ranges) {
            if (range.contains(line)) {
                return range;
            }
        }
        return null;
    }

    /**
     * Get the current line number from the debugger stack trace (0-based).
     * Returns -1 if unable to determine.
     */
    private int getCurrentDebugLine() {
        try {
            JsonObject stackTrace = dapClient.getStackTrace().get();
            LOG.info("getCurrentDebugLine: stackTrace response = " + stackTrace);

            // DAP response might have stackFrames directly or nested in body
            com.google.gson.JsonArray frames = null;

            if (stackTrace.has("stackFrames")) {
                frames = stackTrace.getAsJsonArray("stackFrames");
            } else if (stackTrace.has("body")) {
                JsonObject body = stackTrace.getAsJsonObject("body");
                if (body.has("stackFrames")) {
                    frames = body.getAsJsonArray("stackFrames");
                }
            }

            if (frames != null && !frames.isEmpty()) {
                JsonObject topFrame = frames.get(0).getAsJsonObject();
                LOG.info("getCurrentDebugLine: topFrame = " + topFrame);
                if (topFrame.has("line")) {
                    // DAP uses 1-based lines, convert to 0-based
                    int line = topFrame.get("line").getAsInt() - 1;
                    LOG.info("getCurrentDebugLine: returning line " + line);
                    return line;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error getting stack trace: " + e.getMessage(), e);
        }
        return -1;
    }

    private void evaluateMatchStatements() {
        LOG.info("evaluateMatchStatements called, isEnabled=" + isEnabled());
        if (!isEnabled()) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Editor editor = ReadAction.compute(() -> {
                FileEditorManager fem = FileEditorManager.getInstance(project);
                return fem.getSelectedTextEditor();
            });

            if (editor == null) {
                LOG.info("evaluateMatchStatements: no editor");
                return;
            }

            VirtualFile file = ReadAction.compute(() -> {
                PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                if (psiFile == null || !KarateFileType.INSTANCE.equals(psiFile.getFileType())) {
                    return null;
                }
                return psiFile.getVirtualFile();
            });

            if (file == null) {
                LOG.info("evaluateMatchStatements: no karate file");
                return;
            }

            Document document = editor.getDocument();
            String filePath = file.getPath();
            int lineCount = document.getLineCount();
            LOG.info("evaluateMatchStatements: processing " + filePath + " with " + lineCount + " lines");

            // Get the current debug line and find which scenario we're in
            int currentLine = getCurrentDebugLine();
            LOG.info("evaluateMatchStatements: current debug line=" + currentLine);

            List<ScenarioRange> scenarioRanges = ReadAction.compute(() -> findScenarioRanges(document));
            ScenarioRange currentScenario = findScenarioForLine(scenarioRanges, currentLine);

            if (currentScenario == null) {
                LOG.info("evaluateMatchStatements: not in a scenario, skipping diagnostics");
                // Clear any existing diagnostics since we're not in a scenario
                clearHighlightsForFile(filePath);
                if (registry != null) {
                    registry.clearFailuresForFile(filePath);
                }
                ApplicationManager.getApplication().invokeLater(() -> clearKarateInlays(editor));
                return;
            }

            LOG.info("evaluateMatchStatements: current scenario range [" +
                     currentScenario.startLine + ", " + currentScenario.endLine + "]");

            // Clear previous highlights for this file
            clearHighlightsForFile(filePath);

            // Clear previous failures for this file
            if (registry != null) {
                registry.clearFailuresForFile(filePath);
            }

            KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
            boolean showPassing = settings.isMatchDiagnosticsShowPassing();
            boolean showFailing = settings.isMatchDiagnosticsShowFailing();

            // Only process lines within the current scenario
            for (int lineNum = currentScenario.startLine; lineNum <= currentScenario.endLine && lineNum < lineCount; lineNum++) {
                int startOffset = document.getLineStartOffset(lineNum);
                int endOffset = document.getLineEndOffset(lineNum);
                String lineText = document.getText(new TextRange(startOffset, endOffset));

                Matcher matcher = MATCH_REGEX.matcher(lineText);
                if (!matcher.matches()) continue;

                String matchExpression = matcher.group(2).trim();
                final int line = lineNum;

                try {
                    JsonObject response = dapClient.evaluateMatch(matchExpression).get();
                    String result = response.has("result") ? response.get("result").getAsString() : "";

                    // Debug: log the raw result
                    LOG.warn("evaluateMatch result for line " + line + ": result='" + result.replace("\n", "\\n") + "'");

                    // Skip undefined variables - be very specific about what we skip
                    if (result.contains("is not defined") || result.contains("ReferenceError")) {
                        LOG.warn("  -> SKIPPED: undefined variable");
                        continue;
                    }

                    boolean isPassing = "PASS".equals(result);
                    boolean isFailing = result.startsWith("FAIL:");

                    // Fallback: anything that isn't PASS is treated as a failure
                    // This handles cases like invalid type matchers (e.g., #foo) or other error messages
                    if (!isPassing && !isFailing && !result.isEmpty()) {
                        LOG.warn("  -> Treating as failure (fallback): not PASS and not FAIL:");
                        isFailing = true;
                    }

                    LOG.warn("  -> isPassing=" + isPassing + ", isFailing=" + isFailing +
                             ", showPassing=" + showPassing + ", showFailing=" + showFailing);

                    if (isPassing && showPassing) {
                        addHighlight(editor, startOffset, endOffset, true);
                    } else if (isFailing && showFailing) {
                        addHighlight(editor, startOffset, endOffset, false);

                        // Parse failure and store for quick fix
                        String failureMessage = result.startsWith("FAIL:")
                            ? result.substring(6).trim()
                            : result.trim();

                        // Check if this is an error message (not a normal value mismatch)
                        SimplifiedMessage simplified = simplifyErrorMessage(failureMessage);

                        parseAndStoreFailure(filePath, line, lineText, simplified.message,
                                           document, startOffset, simplified.isErrorMessage);
                    }
                } catch (Exception e) {
                    LOG.warn("Error evaluating match '" + matchExpression + "': " + e.getMessage(), e);
                }
            }

            // Add inlay hints for failures
            addInlayHintsForFailures(editor, filePath);
        });
    }

    /**
     * Result of simplifying an error message.
     */
    private static class SimplifiedMessage {
        final String message;
        final boolean isErrorMessage;

        SimplifiedMessage(String message, boolean isErrorMessage) {
            this.message = message;
            this.isErrorMessage = isErrorMessage;
        }
    }

    /**
     * Simplify error messages by adding a friendly prefix for known error patterns.
     * Keeps the original message but prepends a simplified description.
     * Returns both the message and whether it's an error (vs a normal value mismatch).
     */
    private SimplifiedMessage simplifyErrorMessage(String message) {
        if (message == null || message.isEmpty()) {
            return new SimplifiedMessage(message, false);
        }

        // Strip existing "Match error: " prefix if present to avoid duplication
        String cleanMessage = message;
        if (cleanMessage.startsWith("Match error: ")) {
            cleanMessage = cleanMessage.substring("Match error: ".length());
        }

        // Syntax errors - JS parsing failures
        if (cleanMessage.contains("js failed") || cleanMessage.contains("SyntaxError") ||
            cleanMessage.contains("PolyglotException")) {
            return new SimplifiedMessage("invalid syntax: " + cleanMessage, true);
        }

        // Type cast errors - wrong type for matcher
        if (cleanMessage.contains("cannot be cast to") || cleanMessage.contains("ClassCastException")) {
            return new SimplifiedMessage("invalid type: " + cleanMessage, true);
        }

        // Unknown matcher type
        if (cleanMessage.contains("unknown validator")) {
            return new SimplifiedMessage("unknown matcher: " + cleanMessage, true);
        }

        // Normal value mismatch - not an error message
        return new SimplifiedMessage(cleanMessage, false);
    }

    /**
     * Add inlay hints for match failures directly to the editor.
     */
    private void addInlayHintsForFailures(Editor editor, String filePath) {
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);
        if (!settings.isMatchDiagnosticsShowActualValues()) {
            return;
        }

        if (registry == null) {
            return;
        }

        LOG.info("addInlayHintsForFailures: adding inlays for " + filePath);

        ApplicationManager.getApplication().invokeLater(() -> {
            // Clear existing karate inlays
            clearKarateInlays(editor);

            Map<String, MatchFailureInfo> failures = registry.getAllFailures();
            boolean hasInlays = false;
            for (Map.Entry<String, MatchFailureInfo> entry : failures.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(filePath + ":")) continue;

                MatchFailureInfo failure = entry.getValue();
                addInlayForFailure(editor, failure);
                hasInlays = true;
            }

            // Install mouse listener if we have inlays
            if (hasInlays) {
                installMouseListener(editor);
            }
        });
    }

    /**
     * Install a mouse listener on the editor to handle clicks on inlay [Fix] buttons.
     */
    private void installMouseListener(Editor editor) {
        // Only install once per editor
        if (mouseListeners.containsKey(editor)) {
            return;
        }

        EditorMouseListener listener = new EditorMouseListener() {
            @Override
            public void mouseClicked(@NotNull EditorMouseEvent event) {
                handleInlayClick(event);
            }
        };

        editor.addEditorMouseListener(listener);
        mouseListeners.put(editor, listener);
        LOG.info("Installed mouse listener on editor");
    }

    /**
     * Handle a mouse click event, checking if it's on an inlay [Fix] button.
     */
    private void handleInlayClick(EditorMouseEvent event) {
        Editor editor = event.getEditor();
        java.awt.Point point = event.getMouseEvent().getPoint();

        // Find inlay at this position
        com.intellij.openapi.editor.InlayModel inlayModel = editor.getInlayModel();
        java.util.List<com.intellij.openapi.editor.Inlay<?>> inlays = inlayModel.getAfterLineEndElementsInRange(
            0, editor.getDocument().getTextLength()
        );

        for (com.intellij.openapi.editor.Inlay<?> inlay : inlays) {
            Object renderer = inlay.getRenderer();
            if (renderer instanceof KarateInlayRenderer) {
                KarateInlayRenderer karateRenderer = (KarateInlayRenderer) renderer;

                // Get the inlay bounds
                java.awt.Rectangle bounds = inlay.getBounds();
                if (bounds != null && bounds.contains(point)) {
                    // Calculate relative X within the inlay
                    int relativeX = point.x - bounds.x;

                    if (karateRenderer.isInFixButton(relativeX)) {
                        LOG.info("Fix button clicked!");

                        // Apply the fix (this also updates line highlight to green)
                        karateRenderer.applyFix(editor);

                        // Only dispose this specific inlay, not all of them
                        // Other inlays have RangeMarkers that automatically adjust their offsets
                        com.intellij.openapi.util.Disposer.dispose(inlay);

                        event.consume();
                        return;
                    }
                }
            }
        }
    }

    private void clearKarateInlays(Editor editor) {
        com.intellij.openapi.editor.InlayModel inlayModel = editor.getInlayModel();
        java.util.List<com.intellij.openapi.editor.Inlay<?>> inlays = inlayModel.getAfterLineEndElementsInRange(
            0, editor.getDocument().getTextLength()
        );
        for (com.intellij.openapi.editor.Inlay<?> inlay : inlays) {
            Object renderer = inlay.getRenderer();
            if (renderer instanceof KarateInlayRenderer) {
                com.intellij.openapi.util.Disposer.dispose(inlay);
            }
        }
    }

    private void addInlayForFailure(Editor editor, MatchFailureInfo failure) {
        int lineNumber = failure.lineNumber;
        if (lineNumber < 0 || lineNumber >= editor.getDocument().getLineCount()) {
            return;
        }

        // Create RangeMarker to track document changes
        failure.createRangeMarker(editor.getDocument());

        int lineEndOffset = editor.getDocument().getLineEndOffset(lineNumber);

        String hintText;
        if (failure.isErrorMessage) {
            // Error messages are displayed directly without "actual:" prefix
            hintText = "  " + failure.actualValue + " ";
        } else {
            // Normal value mismatch - show "actual: <value>"
            String displayActual;
            if (failure.isTypeMatcher && failure.actualType != null) {
                displayActual = failure.actualType;
            } else if (failure.isQuoted) {
                displayActual = "'" + failure.actualValue + "'";
            } else {
                displayActual = failure.actualValue;
            }
            hintText = "  actual: " + displayActual + " ";
        }

        LOG.info("addInlayForFailure: adding inlay at line " + lineNumber + ": " + hintText);

        KarateInlayRenderer renderer = new KarateInlayRenderer(hintText, failure, project, this);
        editor.getInlayModel().addAfterLineEndElement(lineEndOffset, true, renderer);
    }

    /**
     * Renderer for Karate inlay hints with clickable [Fix] button.
     */
    static class KarateInlayRenderer implements com.intellij.openapi.editor.EditorCustomElementRenderer {
        private static final String FIX_TEXT = "[Fix]";

        private final String text;
        final MatchFailureInfo failure;
        final Project project;
        final MatchDiagnosticsService service;  // Reference to update highlights
        // Bounds of the [Fix] button relative to inlay start
        int fixButtonRelativeX = 0;
        int fixButtonWidth = 0;

        KarateInlayRenderer(String text, MatchFailureInfo failure, Project project, MatchDiagnosticsService service) {
            this.text = text;
            this.failure = failure;
            this.project = project;
            this.service = service;
        }

        @Override
        public int calcWidthInPixels(com.intellij.openapi.editor.Inlay inlay) {
            Editor editor = inlay.getEditor();
            java.awt.FontMetrics fm = editor.getComponent().getFontMetrics(
                editor.getColorsScheme().getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
            );
            fixButtonRelativeX = fm.stringWidth(text);

            // Don't show [Fix] button for error messages (nothing to fix)
            if (failure.isErrorMessage) {
                fixButtonWidth = 0;
                return fixButtonRelativeX;
            }

            fixButtonWidth = fm.stringWidth(FIX_TEXT);
            return fixButtonRelativeX + fixButtonWidth;
        }

        @Override
        public void paint(com.intellij.openapi.editor.Inlay inlay, java.awt.Graphics g, java.awt.Rectangle targetRegion, com.intellij.openapi.editor.markup.TextAttributes textAttributes) {
            Editor editor = inlay.getEditor();
            java.awt.Font font = editor.getColorsScheme().getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN);
            g.setFont(font);

            // Draw the text in red
            g.setColor(new JBColor(new Color(180, 80, 80), new Color(255, 120, 120)));
            g.drawString(text, targetRegion.x, targetRegion.y + editor.getAscent());

            // Draw [Fix] in a link-like color (blue) - only for non-error messages
            if (!failure.isErrorMessage) {
                g.setColor(new JBColor(new Color(60, 100, 180), new Color(100, 150, 220)));
                int fixX = targetRegion.x + fixButtonRelativeX;
                g.drawString(FIX_TEXT, fixX, targetRegion.y + editor.getAscent());
            }
        }

        /**
         * Check if a point (relative to the inlay's start) is within the [Fix] button.
         */
        boolean isInFixButton(int relativeX) {
            // Error messages don't have a [Fix] button
            if (failure.isErrorMessage) {
                return false;
            }
            return relativeX >= fixButtonRelativeX && relativeX < fixButtonRelativeX + fixButtonWidth;
        }

        void applyFix(Editor editor) {
            // Use getCurrentRange() which uses RangeMarker if available
            TextRange currentRange = failure.getCurrentRange();
            if (currentRange == null) {
                LOG.warn("Cannot apply fix: no valid range available");
                return;
            }

            String replacement;
            if (failure.isTypeMatcher && failure.actualType != null) {
                replacement = typeToMatcher(failure.actualType);
            } else if (failure.isQuoted) {
                replacement = "'" + failure.actualValue + "'";
            } else {
                replacement = failure.actualValue;
            }

            LOG.info("Applying fix: replacing range " + currentRange + " with '" + replacement + "'");

            final String finalReplacement = replacement;
            final int lineNumber = failure.lineNumber;
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Fix Match Value", null, () -> {
                editor.getDocument().replaceString(
                    currentRange.getStartOffset(),
                    currentRange.getEndOffset(),
                    finalReplacement
                );
            });

            // Clear this failure from registry and dispose its RangeMarker
            MatchDiagnosticsRegistry registry = MatchDiagnosticsRegistry.getInstance(project);
            String failureKey = null;
            if (registry != null) {
                // Find and remove the failure
                for (Map.Entry<String, MatchFailureInfo> entry : registry.getAllFailures().entrySet()) {
                    if (entry.getValue() == failure) {
                        failureKey = entry.getKey();
                        break;
                    }
                }
                if (failureKey != null) {
                    registry.clearFailure(failureKey);
                }
            }
            failure.dispose();  // Dispose the RangeMarker

            // Update the line highlight to green (passing)
            if (service != null) {
                service.updateLineHighlightToPassing(editor, lineNumber);
            }
        }

        private static String typeToMatcher(String type) {
            // Handle types that already have # prefix (from detectType)
            switch (type) {
                case "#string":
                case "string": return "'#string'";
                case "#number":
                case "number":
                case "number (decimal)": return "'#number'";
                case "#boolean":
                case "boolean": return "'#boolean'";
                case "#array":
                case "array": return "'#array'";
                case "#object":
                case "object": return "'#object'";
                case "#null":
                case "null": return "'#null'";
                default:
                    // If type already starts with #, just wrap in quotes
                    if (type.startsWith("#")) {
                        return "'" + type + "'";
                    }
                    return "'#" + type + "'";
            }
        }
    }

    // Regex patterns for parsing failure messages
    // Format: "not equal (STRING)\n  'actual'\n  'expected'"
    private static final Pattern FAILURE_QUOTED_REGEX = Pattern.compile(
        "not equal \\([^)]+\\)\\n\\s*'([^']+)'\\n\\s*'([^']+)'"
    );
    // Format: "not equal (NUMBER)\n  123\n  456"
    private static final Pattern FAILURE_UNQUOTED_REGEX = Pattern.compile(
        "not equal \\(([^)]+)\\)\\n\\s*(\\S+)\\n\\s*(\\S+)"
    );
    // Format: "not a string (LIST:STRING)\n  [actual_value]\n  '#string'"
    // Note: actual value may be unquoted (JSON) or quoted (string)
    private static final Pattern FAILURE_TYPE_MISMATCH_REGEX = Pattern.compile(
        "not (?:an? )?([^(]+) \\([^)]+\\)\\n\\s*(.+?)\\n\\s*'([^']+)'"
    );

    private void parseAndStoreFailure(String filePath, int lineNum, String lineText, String message,
                                       Document document, int lineStartOffset, boolean isErrorMessage) {
        String key = filePath + ":" + lineNum;

        // Debug: log the failure message to help diagnose parsing issues
        LOG.info("parseAndStoreFailure: lineNum=" + lineNum + ", isError=" + isErrorMessage +
                 ", message='" + message.replace("\n", "\\n") + "'");

        // If it's an error message, store it directly without trying to parse
        if (isErrorMessage) {
            if (registry != null) {
                registry.addFailure(key, new MatchFailureInfo(
                    lineNum, "", message, null, false, false, null, true
                ));
            }
            return;
        }

        // Try quoted string format first
        java.util.regex.Matcher quotedMatch = FAILURE_QUOTED_REGEX.matcher(message);
        if (quotedMatch.find()) {
            String actualValue = quotedMatch.group(1);
            String expectedValue = quotedMatch.group(2);
            ExpectedRangeResult result = findExpectedRange(lineText, expectedValue, lineStartOffset);

            if (result != null && registry != null) {
                registry.addFailure(key, new MatchFailureInfo(
                    lineNum, expectedValue, actualValue, result.range, result.isQuoted, false, null
                ));
                return;
            }
        }

        // Try unquoted format
        java.util.regex.Matcher unquotedMatch = FAILURE_UNQUOTED_REGEX.matcher(message);
        if (unquotedMatch.find()) {
            String actualValue = unquotedMatch.group(2);
            String expectedValue = unquotedMatch.group(3);
            ExpectedRangeResult result = findExpectedRange(lineText, expectedValue, lineStartOffset);

            if (result != null && registry != null) {
                registry.addFailure(key, new MatchFailureInfo(
                    lineNum, expectedValue, actualValue, result.range, result.isQuoted, false, null
                ));
                return;
            }
        }

        // Try type mismatch format (e.g., "#array" vs actual string)
        java.util.regex.Matcher typeMismatchMatch = FAILURE_TYPE_MISMATCH_REGEX.matcher(message);
        if (typeMismatchMatch.find()) {
            String mismatchType = typeMismatchMatch.group(1).trim();
            String actualValue = typeMismatchMatch.group(2).trim();
            String expectedValue = typeMismatchMatch.group(3);

            // Strip quotes from actual value if present
            if (actualValue.startsWith("'") && actualValue.endsWith("'")) {
                actualValue = actualValue.substring(1, actualValue.length() - 1);
            }

            boolean isTypeMatcher = expectedValue.startsWith("#");
            ExpectedRangeResult result = findExpectedRange(lineText, expectedValue, lineStartOffset);

            if (result != null && registry != null) {
                String actualType = detectType(actualValue);
                registry.addFailure(key, new MatchFailureInfo(
                    lineNum, expectedValue, actualValue, result.range, result.isQuoted, isTypeMatcher, actualType
                ));
                return;
            }
        }

        // Fallback: store basic failure info without range (quick fix won't work)
        if (registry != null) {
            registry.addFailure(key, new MatchFailureInfo(
                lineNum, "", message, null, false, false, null
            ));
        }
    }

    /**
     * Result of finding expected value range, includes whether it was quoted.
     */
    private static class ExpectedRangeResult {
        final TextRange range;
        final boolean isQuoted;

        ExpectedRangeResult(TextRange range, boolean isQuoted) {
            this.range = range;
            this.isQuoted = isQuoted;
        }
    }

    /**
     * Find the TextRange of the expected value in the line.
     * Looks for patterns like "== expectedValue" or "== 'expectedValue'" at the end of the line.
     * Tries both quoted and unquoted patterns to handle type matchers like '#string'.
     */
    private ExpectedRangeResult findExpectedRange(String lineText, String expectedValue, int lineStartOffset) {
        // Find == followed by the expected value
        int equalsIdx = lineText.lastIndexOf("==");
        if (equalsIdx == -1) return null;

        String afterEquals = lineText.substring(equalsIdx + 2);

        // Try quoted version first (most common in Karate)
        String quotedPattern = "'" + expectedValue + "'";
        int quotedIdx = afterEquals.indexOf(quotedPattern);
        if (quotedIdx != -1) {
            int absoluteStart = lineStartOffset + equalsIdx + 2 + quotedIdx;
            return new ExpectedRangeResult(
                new TextRange(absoluteStart, absoluteStart + quotedPattern.length()),
                true
            );
        }

        // Try unquoted version (for numbers, booleans, unquoted type matchers)
        int unquotedIdx = afterEquals.indexOf(expectedValue);
        if (unquotedIdx != -1) {
            int absoluteStart = lineStartOffset + equalsIdx + 2 + unquotedIdx;
            return new ExpectedRangeResult(
                new TextRange(absoluteStart, absoluteStart + expectedValue.length()),
                false
            );
        }

        return null;
    }

    /**
     * Detect the type of a value for type matcher quick fixes.
     */
    private String detectType(String value) {
        if (value == null) return null;

        // Check for common types
        if (value.matches("-?\\d+")) return "#number";
        if (value.matches("-?\\d+\\.\\d+")) return "#number";
        if ("true".equals(value) || "false".equals(value)) return "#boolean";
        if ("null".equals(value)) return "#null";
        if (value.startsWith("[")) return "#array";
        if (value.startsWith("{")) return "#object";

        return "#string";
    }

    private void addHighlight(Editor editor, int startOffset, int endOffset, boolean isPassing) {
        ApplicationManager.getApplication().invokeLater(() -> {
            MarkupModel markupModel = editor.getMarkupModel();

            TextAttributes attributes = new TextAttributes();
            attributes.setBackgroundColor(isPassing ? PASS_COLOR : FAIL_COLOR);

            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.WARNING,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
            );

            // Store highlighter for later cleanup
            VirtualFile file = editor.getVirtualFile();
            if (file != null) {
                highlightersByFile.computeIfAbsent(file.getPath(), k -> new ArrayList<>())
                    .add(highlighter);
            }
        });
    }

    /**
     * Update the line highlight from failing (red) to passing (green).
     * Called after a fix is applied.
     */
    void updateLineHighlightToPassing(Editor editor, int lineNumber) {
        if (lineNumber < 0 || lineNumber >= editor.getDocument().getLineCount()) {
            return;
        }

        int startOffset = editor.getDocument().getLineStartOffset(lineNumber);
        int endOffset = editor.getDocument().getLineEndOffset(lineNumber);
        VirtualFile file = editor.getVirtualFile();
        String filePath = file != null ? file.getPath() : null;

        ApplicationManager.getApplication().invokeLater(() -> {
            MarkupModel markupModel = editor.getMarkupModel();

            // Find and remove existing highlighter for this line
            if (filePath != null) {
                java.util.List<RangeHighlighter> highlighters = highlightersByFile.get(filePath);
                if (highlighters != null) {
                    java.util.Iterator<RangeHighlighter> iterator = highlighters.iterator();
                    while (iterator.hasNext()) {
                        RangeHighlighter h = iterator.next();
                        if (h.isValid()) {
                            // Check if this highlighter is on the same line
                            int hLine = editor.getDocument().getLineNumber(h.getStartOffset());
                            if (hLine == lineNumber) {
                                h.dispose();
                                iterator.remove();
                            }
                        }
                    }
                }
            }

            // Add new green highlight
            TextAttributes attributes = new TextAttributes();
            attributes.setBackgroundColor(PASS_COLOR);

            RangeHighlighter newHighlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.WARNING,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
            );

            if (filePath != null) {
                highlightersByFile.computeIfAbsent(filePath, k -> new ArrayList<>())
                    .add(newHighlighter);
            }
        });
    }

    private void clearHighlights() {
        for (String filePath : highlightersByFile.keySet()) {
            clearHighlightsForFile(filePath);
        }
        highlightersByFile.clear();

        // Also clear all inlays from all open editors
        clearAllInlays();
    }

    private void clearHighlightsForFile(String filePath) {
        java.util.List<RangeHighlighter> highlighters = highlightersByFile.remove(filePath);
        if (highlighters == null) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            for (RangeHighlighter highlighter : highlighters) {
                if (highlighter.isValid()) {
                    highlighter.dispose();
                }
            }
        });
    }

    /**
     * Clear all Karate inlays from all open editors.
     */
    private void clearAllInlays() {
        ApplicationManager.getApplication().invokeLater(() -> {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            for (FileEditor fileEditor : fem.getAllEditors()) {
                if (fileEditor instanceof TextEditor) {
                    Editor editor = ((TextEditor) fileEditor).getEditor();
                    clearKarateInlays(editor);
                }
            }
        });
    }
}

