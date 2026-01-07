package com.j8d.karate.intellij.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * Syntax highlighter for Karate feature files.
 */
public class KarateSyntaxHighlighter extends SyntaxHighlighterBase {
    
    public static final TextAttributesKey KEYWORD = createTextAttributesKey(
        "KARATE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey STEP_KEYWORD = createTextAttributesKey(
        "KARATE_STEP_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
    public static final TextAttributesKey TAG = createTextAttributesKey(
        "KARATE_TAG", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey COMMENT = createTextAttributesKey(
        "KARATE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
    public static final TextAttributesKey STRING = createTextAttributesKey(
        "KARATE_STRING", DefaultLanguageHighlighterColors.STRING);
    public static final TextAttributesKey NUMBER = createTextAttributesKey(
        "KARATE_NUMBER", DefaultLanguageHighlighterColors.NUMBER);
    public static final TextAttributesKey TEXT = createTextAttributesKey(
        "KARATE_TEXT", HighlighterColors.TEXT);
    public static final TextAttributesKey TABLE = createTextAttributesKey(
        "KARATE_TABLE", DefaultLanguageHighlighterColors.MARKUP_TAG);
    
    private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{KEYWORD};
    private static final TextAttributesKey[] STEP_KEYWORD_KEYS = new TextAttributesKey[]{STEP_KEYWORD};
    private static final TextAttributesKey[] TAG_KEYS = new TextAttributesKey[]{TAG};
    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[]{COMMENT};
    private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{STRING};
    private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{NUMBER};
    private static final TextAttributesKey[] TEXT_KEYS = new TextAttributesKey[]{TEXT};
    private static final TextAttributesKey[] TABLE_KEYS = new TextAttributesKey[]{TABLE};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];
    
    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new KarateLexer();
    }
    
    @Override
    @NotNull
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        if (tokenType.equals(KarateTokenTypes.FEATURE_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.SCENARIO_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.BACKGROUND_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.EXAMPLES_KEYWORD)) {
            return KEYWORD_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.STEP_KEYWORD)) {
            return STEP_KEYWORD_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.TAG)) {
            return TAG_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.COMMENT)) {
            return COMMENT_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.STRING) ||
            tokenType.equals(KarateTokenTypes.DOC_STRING)) {
            return STRING_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.NUMBER)) {
            return NUMBER_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.PIPE) ||
            tokenType.equals(KarateTokenTypes.TABLE_CELL)) {
            return TABLE_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.TEXT)) {
            return TEXT_KEYS;
        }
        return EMPTY_KEYS;
    }
}

