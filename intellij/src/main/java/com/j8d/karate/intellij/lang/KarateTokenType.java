package com.j8d.karate.intellij.lang;

import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/**
 * Token type for Karate language elements.
 */
public class KarateTokenType extends IElementType {
    
    public KarateTokenType(@NotNull String debugName) {
        super(debugName, KarateLanguage.INSTANCE);
    }
    
    @Override
    public String toString() {
        return "KarateTokenType." + super.toString();
    }
}

