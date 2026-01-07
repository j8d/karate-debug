package com.j8d.karate.intellij.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import org.jetbrains.annotations.NotNull;

/**
 * PSI file for Karate feature files.
 */
public class KarateFile extends PsiFileBase {
    
    public KarateFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, KarateLanguage.INSTANCE);
    }
    
    @Override
    public @NotNull FileType getFileType() {
        return KarateFileType.INSTANCE;
    }
    
    @Override
    public String toString() {
        return "Karate Feature File";
    }
}

