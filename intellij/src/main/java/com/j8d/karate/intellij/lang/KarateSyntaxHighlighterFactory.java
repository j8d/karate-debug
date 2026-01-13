package com.j8d.karate.intellij.lang;

import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating Karate syntax highlighters.
 */
public class KarateSyntaxHighlighterFactory extends SyntaxHighlighterFactory {
    
    @Override
    public @NotNull SyntaxHighlighter getSyntaxHighlighter(@Nullable Project project,
                                                            @Nullable VirtualFile virtualFile) {
        return new KarateSyntaxHighlighter();
    }
}

