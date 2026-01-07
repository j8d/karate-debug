package com.j8d.karate.intellij.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides editor support for the Karate debugger.
 * Required by IntelliJ's XDebugger for the variables view.
 */
public class KarateEditorsProvider extends XDebuggerEditorsProvider {

    @Override
    public @NotNull FileType getFileType() {
        // Use plain text for now - could be enhanced to support Karate expressions
        return PlainTextFileType.INSTANCE;
    }

    @Override
    public @NotNull Document createDocument(@NotNull Project project,
                                             @NotNull String text,
                                             @Nullable XSourcePosition sourcePosition,
                                             @NotNull EvaluationMode mode) {
        return EditorFactory.getInstance().createDocument(text);
    }
}

