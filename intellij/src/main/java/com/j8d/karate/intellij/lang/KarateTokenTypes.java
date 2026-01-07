package com.j8d.karate.intellij.lang;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/**
 * Token types for Karate language.
 */
public interface KarateTokenTypes {
    
    IElementType FEATURE_KEYWORD = new KarateTokenType("FEATURE_KEYWORD");
    IElementType SCENARIO_KEYWORD = new KarateTokenType("SCENARIO_KEYWORD");
    IElementType SCENARIO_OUTLINE_KEYWORD = new KarateTokenType("SCENARIO_OUTLINE_KEYWORD");
    IElementType BACKGROUND_KEYWORD = new KarateTokenType("BACKGROUND_KEYWORD");
    IElementType EXAMPLES_KEYWORD = new KarateTokenType("EXAMPLES_KEYWORD");
    
    IElementType STEP_KEYWORD = new KarateTokenType("STEP_KEYWORD");
    IElementType TAG = new KarateTokenType("TAG");
    IElementType COMMENT = new KarateTokenType("COMMENT");
    IElementType TEXT = new KarateTokenType("TEXT");
    IElementType STRING = new KarateTokenType("STRING");
    IElementType NUMBER = new KarateTokenType("NUMBER");
    IElementType WHITESPACE = new KarateTokenType("WHITESPACE");
    IElementType NEWLINE = new KarateTokenType("NEWLINE");
    IElementType DOC_STRING = new KarateTokenType("DOC_STRING");
    IElementType TABLE_CELL = new KarateTokenType("TABLE_CELL");
    IElementType PIPE = new KarateTokenType("PIPE");
    
    TokenSet KEYWORDS = TokenSet.create(
        FEATURE_KEYWORD, SCENARIO_KEYWORD, SCENARIO_OUTLINE_KEYWORD,
        BACKGROUND_KEYWORD, EXAMPLES_KEYWORD, STEP_KEYWORD
    );
    
    TokenSet COMMENTS = TokenSet.create(COMMENT);
    TokenSet STRINGS = TokenSet.create(STRING, DOC_STRING);
    TokenSet WHITESPACES = TokenSet.create(WHITESPACE, NEWLINE);
}

