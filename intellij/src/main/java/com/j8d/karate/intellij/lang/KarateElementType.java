package com.j8d.karate.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Element type for Karate PSI elements.
 */
public class KarateElementType extends IElementType {
    
    public KarateElementType(@NotNull String debugName) {
        super(debugName, KarateLanguage.INSTANCE);
    }
}

