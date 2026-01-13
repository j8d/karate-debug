package com.j8d.karate.intellij.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

/**
 * Parser definition for Karate language.
 */
public class KarateParserDefinition implements ParserDefinition {
    
    public static final IFileElementType FILE = new IFileElementType(KarateLanguage.INSTANCE);
    
    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new KarateLexer();
    }
    
    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new KarateParser();
    }
    
    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }
    
    @Override
    public @NotNull TokenSet getCommentTokens() {
        return KarateTokenTypes.COMMENTS;
    }
    
    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return KarateTokenTypes.STRINGS;
    }
    
    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return KarateTokenTypes.WHITESPACES;
    }
    
    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return new KaratePsiElement(node);
    }
    
    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new KarateFile(viewProvider);
    }
}

