package com.j8d.karate.intellij.lang;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

/**
 * Base PSI element for Karate language.
 */
public class KaratePsiElement extends ASTWrapperPsiElement {
    
    public KaratePsiElement(@NotNull ASTNode node) {
        super(node);
    }
}

