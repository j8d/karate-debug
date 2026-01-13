package com.j8d.karate.intellij.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * File type for Karate .feature files.
 */
public class KarateFileType extends LanguageFileType {
    
    public static final KarateFileType INSTANCE = new KarateFileType();
    
    private KarateFileType() {
        super(KarateLanguage.INSTANCE);
    }
    
    @Override
    public @NotNull String getName() {
        return "Karate Feature";
    }
    
    @Override
    public @NotNull String getDescription() {
        return "Karate feature file";
    }
    
    @Override
    public @NotNull String getDefaultExtension() {
        return "feature";
    }
    
    @Override
    public @Nullable Icon getIcon() {
        return IconLoader.getIcon("/icons/karate-13.svg", KarateFileType.class);
    }
}

