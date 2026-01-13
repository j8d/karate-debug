package com.j8d.karate.intellij.lang;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

/**
 * Token types for Karate language.
 */
public interface KarateTokenTypes {

    // Gherkin structure keywords
    IElementType FEATURE_KEYWORD = new KarateTokenType("FEATURE_KEYWORD");
    IElementType SCENARIO_KEYWORD = new KarateTokenType("SCENARIO_KEYWORD");
    IElementType SCENARIO_OUTLINE_KEYWORD = new KarateTokenType("SCENARIO_OUTLINE_KEYWORD");
    IElementType BACKGROUND_KEYWORD = new KarateTokenType("BACKGROUND_KEYWORD");
    IElementType EXAMPLES_KEYWORD = new KarateTokenType("EXAMPLES_KEYWORD");
    IElementType STEP_KEYWORD = new KarateTokenType("STEP_KEYWORD");

    // Karate-specific keywords (def, set, match, url, etc.)
    IElementType KARATE_KEYWORD = new KarateTokenType("KARATE_KEYWORD");

    // HTTP methods (GET, POST, PUT, DELETE, etc.)
    IElementType HTTP_METHOD = new KarateTokenType("HTTP_METHOD");

    // Match type markers (#ignore, #notnull, #array, etc.)
    IElementType MATCH_TYPE = new KarateTokenType("MATCH_TYPE");

    // Variables (<placeholder> and #(expression))
    IElementType VARIABLE = new KarateTokenType("VARIABLE");
    IElementType EMBEDDED_EXPRESSION = new KarateTokenType("EMBEDDED_EXPRESSION");

    // Operators (==, !=, &&, ||, etc.)
    IElementType OPERATOR = new KarateTokenType("OPERATOR");

    // JSON/structure tokens
    IElementType BRACE = new KarateTokenType("BRACE");
    IElementType BRACKET = new KarateTokenType("BRACKET");
    IElementType COLON = new KarateTokenType("COLON");
    IElementType COMMA = new KarateTokenType("COMMA");

    // JSON literals
    IElementType JSON_BOOLEAN = new KarateTokenType("JSON_BOOLEAN");
    IElementType JSON_NULL = new KarateTokenType("JSON_NULL");

    // Java/JavaScript tokens
    IElementType JAVA_CLASS = new KarateTokenType("JAVA_CLASS");         // java.util.UUID, java.time.ZoneOffset
    IElementType METHOD_CALL = new KarateTokenType("METHOD_CALL");       // .toString(), .format()
    IElementType FUNCTION_KEYWORD = new KarateTokenType("FUNCTION_KEYWORD"); // function keyword
    IElementType ARROW_FUNCTION = new KarateTokenType("ARROW_FUNCTION"); // =>

    // Basic tokens
    IElementType TAG = new KarateTokenType("TAG");
    IElementType COMMENT = new KarateTokenType("COMMENT");
    IElementType TEXT = new KarateTokenType("TEXT");
    IElementType STRING = new KarateTokenType("STRING");
    IElementType NUMBER = new KarateTokenType("NUMBER");
    IElementType WHITESPACE = new KarateTokenType("WHITESPACE");
    IElementType NEWLINE = new KarateTokenType("NEWLINE");
    IElementType DOC_STRING = new KarateTokenType("DOC_STRING");
    IElementType DOC_STRING_DELIMITER = new KarateTokenType("DOC_STRING_DELIMITER");
    IElementType TABLE_CELL = new KarateTokenType("TABLE_CELL");
    IElementType PIPE = new KarateTokenType("PIPE");

    TokenSet KEYWORDS = TokenSet.create(
        FEATURE_KEYWORD, SCENARIO_KEYWORD, SCENARIO_OUTLINE_KEYWORD,
        BACKGROUND_KEYWORD, EXAMPLES_KEYWORD, STEP_KEYWORD, KARATE_KEYWORD
    );

    TokenSet COMMENTS = TokenSet.create(COMMENT);
    TokenSet STRINGS = TokenSet.create(STRING, DOC_STRING);
    TokenSet WHITESPACES = TokenSet.create(WHITESPACE, NEWLINE);
}

