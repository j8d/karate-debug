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
 * Maps token types to colors for consistent highlighting with VS Code.
 */
public class KarateSyntaxHighlighter extends SyntaxHighlighterBase {

    // Gherkin structure keywords (Feature, Scenario, etc.) - purple/keyword color
    public static final TextAttributesKey KEYWORD = createTextAttributesKey(
        "KARATE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);

    // Step keywords (Given, When, Then, etc.) - purple/keyword color (same as VS Code)
    public static final TextAttributesKey STEP_KEYWORD = createTextAttributesKey(
        "KARATE_STEP_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);

    // Karate keywords (def, set, match, url, etc.) - blue/function color (distinct from step)
    public static final TextAttributesKey KARATE_KEYWORD = createTextAttributesKey(
        "KARATE_ACTION_KEYWORD", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);

    // HTTP methods (GET, POST, etc.) - bold blue/static method
    public static final TextAttributesKey HTTP_METHOD = createTextAttributesKey(
        "KARATE_HTTP_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD);

    // Match type markers (#ignore, #notnull, etc.) - magenta/constant
    public static final TextAttributesKey MATCH_TYPE = createTextAttributesKey(
        "KARATE_MATCH_TYPE", DefaultLanguageHighlighterColors.CONSTANT);

    // Variables (<placeholder> and #(expression)) - cyan/parameter
    public static final TextAttributesKey VARIABLE = createTextAttributesKey(
        "KARATE_VARIABLE", DefaultLanguageHighlighterColors.PARAMETER);

    // Tags (@tag) - yellow/metadata
    public static final TextAttributesKey TAG = createTextAttributesKey(
        "KARATE_TAG", DefaultLanguageHighlighterColors.METADATA);

    // Comments - gray
    public static final TextAttributesKey COMMENT = createTextAttributesKey(
        "KARATE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);

    // Strings - orange/string color (rusty orange in most themes)
    public static final TextAttributesKey STRING = createTextAttributesKey(
        "KARATE_STRING", DefaultLanguageHighlighterColors.STRING);

    // Numbers
    public static final TextAttributesKey NUMBER = createTextAttributesKey(
        "KARATE_NUMBER", DefaultLanguageHighlighterColors.NUMBER);

    // JSON literals (true, false, null)
    public static final TextAttributesKey JSON_LITERAL = createTextAttributesKey(
        "KARATE_JSON_LITERAL", DefaultLanguageHighlighterColors.KEYWORD);

    // Operators
    public static final TextAttributesKey OPERATOR = createTextAttributesKey(
        "KARATE_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    // Structure characters (braces, brackets)
    public static final TextAttributesKey BRACES = createTextAttributesKey(
        "KARATE_BRACES", DefaultLanguageHighlighterColors.BRACES);
    public static final TextAttributesKey BRACKETS = createTextAttributesKey(
        "KARATE_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS);

    // Java class names (java.util.UUID, java.time.ZoneOffset)
    public static final TextAttributesKey JAVA_CLASS = createTextAttributesKey(
        "KARATE_JAVA_CLASS", DefaultLanguageHighlighterColors.CLASS_NAME);

    // Method calls (.toString(), .format()) - use STATIC_METHOD for yellow-ish color
    public static final TextAttributesKey METHOD_CALL = createTextAttributesKey(
        "KARATE_METHOD_CALL", DefaultLanguageHighlighterColors.STATIC_METHOD);

    // JavaScript function keyword
    public static final TextAttributesKey FUNCTION_KEYWORD = createTextAttributesKey(
        "KARATE_FUNCTION_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);

    // Arrow function operator (=>)
    public static final TextAttributesKey ARROW_FUNCTION = createTextAttributesKey(
        "KARATE_ARROW_FUNCTION", DefaultLanguageHighlighterColors.OPERATION_SIGN);

    // Table elements
    public static final TextAttributesKey TABLE = createTextAttributesKey(
        "KARATE_TABLE", DefaultLanguageHighlighterColors.MARKUP_TAG);

    // Plain text
    public static final TextAttributesKey TEXT = createTextAttributesKey(
        "KARATE_TEXT", HighlighterColors.TEXT);

    // Key arrays
    private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{KEYWORD};
    private static final TextAttributesKey[] STEP_KEYWORD_KEYS = new TextAttributesKey[]{STEP_KEYWORD};
    private static final TextAttributesKey[] KARATE_KEYWORD_KEYS = new TextAttributesKey[]{KARATE_KEYWORD};
    private static final TextAttributesKey[] HTTP_METHOD_KEYS = new TextAttributesKey[]{HTTP_METHOD};
    private static final TextAttributesKey[] MATCH_TYPE_KEYS = new TextAttributesKey[]{MATCH_TYPE};
    private static final TextAttributesKey[] VARIABLE_KEYS = new TextAttributesKey[]{VARIABLE};
    private static final TextAttributesKey[] TAG_KEYS = new TextAttributesKey[]{TAG};
    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[]{COMMENT};
    private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{STRING};
    private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{NUMBER};
    private static final TextAttributesKey[] JSON_LITERAL_KEYS = new TextAttributesKey[]{JSON_LITERAL};
    private static final TextAttributesKey[] OPERATOR_KEYS = new TextAttributesKey[]{OPERATOR};
    private static final TextAttributesKey[] BRACES_KEYS = new TextAttributesKey[]{BRACES};
    private static final TextAttributesKey[] BRACKETS_KEYS = new TextAttributesKey[]{BRACKETS};
    private static final TextAttributesKey[] JAVA_CLASS_KEYS = new TextAttributesKey[]{JAVA_CLASS};
    private static final TextAttributesKey[] METHOD_CALL_KEYS = new TextAttributesKey[]{METHOD_CALL};
    private static final TextAttributesKey[] FUNCTION_KEYWORD_KEYS = new TextAttributesKey[]{FUNCTION_KEYWORD};
    private static final TextAttributesKey[] ARROW_FUNCTION_KEYS = new TextAttributesKey[]{ARROW_FUNCTION};
    private static final TextAttributesKey[] TABLE_KEYS = new TextAttributesKey[]{TABLE};
    private static final TextAttributesKey[] TEXT_KEYS = new TextAttributesKey[]{TEXT};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new KarateLexer();
    }

    @Override
    @NotNull
    public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
        // Gherkin structure keywords
        if (tokenType.equals(KarateTokenTypes.FEATURE_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.SCENARIO_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.BACKGROUND_KEYWORD) ||
            tokenType.equals(KarateTokenTypes.EXAMPLES_KEYWORD)) {
            return KEYWORD_KEYS;
        }

        // Step keywords
        if (tokenType.equals(KarateTokenTypes.STEP_KEYWORD)) {
            return STEP_KEYWORD_KEYS;
        }

        // Karate action keywords
        if (tokenType.equals(KarateTokenTypes.KARATE_KEYWORD)) {
            return KARATE_KEYWORD_KEYS;
        }

        // HTTP methods
        if (tokenType.equals(KarateTokenTypes.HTTP_METHOD)) {
            return HTTP_METHOD_KEYS;
        }

        // Match type markers
        if (tokenType.equals(KarateTokenTypes.MATCH_TYPE)) {
            return MATCH_TYPE_KEYS;
        }

        // Variables
        if (tokenType.equals(KarateTokenTypes.VARIABLE) ||
            tokenType.equals(KarateTokenTypes.EMBEDDED_EXPRESSION)) {
            return VARIABLE_KEYS;
        }

        // Tags
        if (tokenType.equals(KarateTokenTypes.TAG)) {
            return TAG_KEYS;
        }

        // Comments
        if (tokenType.equals(KarateTokenTypes.COMMENT)) {
            return COMMENT_KEYS;
        }

        // Strings
        if (tokenType.equals(KarateTokenTypes.STRING) ||
            tokenType.equals(KarateTokenTypes.DOC_STRING) ||
            tokenType.equals(KarateTokenTypes.DOC_STRING_DELIMITER)) {
            return STRING_KEYS;
        }

        // Numbers
        if (tokenType.equals(KarateTokenTypes.NUMBER)) {
            return NUMBER_KEYS;
        }

        // JSON literals
        if (tokenType.equals(KarateTokenTypes.JSON_BOOLEAN) ||
            tokenType.equals(KarateTokenTypes.JSON_NULL)) {
            return JSON_LITERAL_KEYS;
        }

        // Operators
        if (tokenType.equals(KarateTokenTypes.OPERATOR)) {
            return OPERATOR_KEYS;
        }

        // Braces and brackets
        if (tokenType.equals(KarateTokenTypes.BRACE)) {
            return BRACES_KEYS;
        }
        if (tokenType.equals(KarateTokenTypes.BRACKET)) {
            return BRACKETS_KEYS;
        }

        // Java class names - use STRING color (green) like VS Code
        if (tokenType.equals(KarateTokenTypes.JAVA_CLASS)) {
            return STRING_KEYS;
        }

        // Method calls
        if (tokenType.equals(KarateTokenTypes.METHOD_CALL)) {
            return METHOD_CALL_KEYS;
        }

        // Function keyword
        if (tokenType.equals(KarateTokenTypes.FUNCTION_KEYWORD)) {
            return FUNCTION_KEYWORD_KEYS;
        }

        // Arrow function
        if (tokenType.equals(KarateTokenTypes.ARROW_FUNCTION)) {
            return ARROW_FUNCTION_KEYS;
        }

        // Table elements
        if (tokenType.equals(KarateTokenTypes.PIPE) ||
            tokenType.equals(KarateTokenTypes.TABLE_CELL)) {
            return TABLE_KEYS;
        }

        // Plain text
        if (tokenType.equals(KarateTokenTypes.TEXT)) {
            return TEXT_KEYS;
        }

        return EMPTY_KEYS;
    }
}

