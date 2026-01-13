package com.j8d.karate.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexer for Karate feature files.
 * Tokenizes the file into keywords, steps, strings, variables, etc.
 */
public class KarateLexer extends LexerBase {

    private CharSequence buffer;
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    // State for doc strings
    private static final int STATE_NORMAL = 0;
    private static final int STATE_DOC_STRING = 1;
    private int state = STATE_NORMAL;

    // Line-start patterns (must match at beginning of line after whitespace)
    private static final Pattern FEATURE = Pattern.compile("Feature:");
    private static final Pattern SCENARIO = Pattern.compile("Scenario:");
    private static final Pattern SCENARIO_OUTLINE = Pattern.compile("Scenario Outline:");
    private static final Pattern BACKGROUND = Pattern.compile("Background:");
    private static final Pattern EXAMPLES = Pattern.compile("Examples:");
    private static final Pattern STEP = Pattern.compile("(Given|When|Then|And|But)\\b|\\*");
    private static final Pattern TAG = Pattern.compile("@[\\w\\-=,]+");
    // Comments: # followed by text, but NOT match markers like #string, #notnull, #[], #()
    private static final Pattern COMMENT = Pattern.compile("#(?!\\(|\\[|ignore|notnull|null|present|notpresent|array|object|boolean|number|string|uuid|regex|\\?).*");
    private static final Pattern DOC_STRING_DELIM = Pattern.compile("\"\"\"");
    private static final Pattern TABLE_PIPE = Pattern.compile("\\|");

    // Inline patterns
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t]+");
    private static final Pattern NEWLINE = Pattern.compile("[\\r\\n]+");

    // Karate keywords - no leading \b (same structure as STEP which works)
    // Includes JavaScript keywords commonly used in Karate expressions
    private static final Pattern KARATE_KEYWORD_PATTERN = Pattern.compile(
        "(def|set|print|assert|match|contains|read|call|callonce|" +
        "karate|configure|url|path|request|method|status|response|" +
        "param|params|header|headers|cookie|cookies|eval|listen|" +
        "doc|table|text|replace|yaml|csv|bytes|copy|remove|" +
        "json|xml|xmlstring|string|typeOf|sleep|retry|until|" +
        "multipart|file|field|fields|entity|form|soap|driver|" +
        "only|any|each|deep|not|if|new|var|return|let|const)\\b"
    );

    // HTTP methods
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile(
        "(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|CONNECT|TRACE)\\b"
    );

    // Match type markers
    private static final Pattern MATCH_TYPE = Pattern.compile(
        "#(ignore|notnull|null|present|notpresent|array|object|boolean|number|string|uuid|regex|\\?|\\[.+?\\])\\b?"
    );

    // Embedded expression #(...)
    private static final Pattern EMBEDDED_EXPR = Pattern.compile("#\\([^)]*\\)");

    // Placeholder variable <...> - only simple identifiers like <name>, <userId>
    // Must be word characters only to avoid matching XML tags like <soapenv:Body>
    private static final Pattern PLACEHOLDER = Pattern.compile("<[a-zA-Z_][a-zA-Z0-9_]*>");

    // Java fully-qualified class names: java.util.UUID, java.time.ZoneOffset.UTC
    // Match java.package.ClassName and optional static fields like .UTC
    private static final Pattern JAVA_CLASS = Pattern.compile(
        "java(?:\\.[a-z][a-zA-Z0-9]*)+\\.[A-Z][a-zA-Z0-9]*(?:\\.[A-Z][A-Z0-9_]*)?"
    );

    // Method calls: .methodName( - matches the dot and method name before paren
    private static final Pattern METHOD_CALL = Pattern.compile("\\.[a-zA-Z_][a-zA-Z0-9_]*(?=\\s*\\()");

    // JavaScript function keyword (standalone for special highlighting)
    private static final Pattern FUNCTION_KEYWORD = Pattern.compile("\\bfunction\\b");

    // Arrow function operator
    private static final Pattern ARROW_FUNCTION = Pattern.compile("=>");

    // Strings
    private static final Pattern SINGLE_STRING = Pattern.compile("'[^']*'");
    private static final Pattern DOUBLE_STRING = Pattern.compile("\"[^\"]*\"");

    // Numbers
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");

    // JSON literals
    private static final Pattern JSON_BOOLEAN = Pattern.compile("\\b(true|false)\\b");
    private static final Pattern JSON_NULL = Pattern.compile("\\bnull\\b");

    // Operators
    private static final Pattern OPERATOR = Pattern.compile("(==|!=|&&|\\|\\||>=|<=|[><])");

    // Structure characters
    private static final Pattern BRACE = Pattern.compile("[{}]");
    private static final Pattern BRACKET = Pattern.compile("[\\[\\]]");
    private static final Pattern COLON = Pattern.compile(":");
    private static final Pattern COMMA = Pattern.compile(",");

    // Word pattern for keyword matching
    private static final Pattern WORD = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.state = initialState;
        advance();
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;

        if (tokenStart >= bufferEnd) {
            tokenType = null;
            return;
        }

        CharSequence remaining = buffer.subSequence(tokenStart, bufferEnd);

        // Handle doc string state - keep it simple, treat as plain string
        // Only highlight embedded expressions #(...) within docstrings
        if (state == STATE_DOC_STRING) {
            if (tryMatch(remaining, DOC_STRING_DELIM, KarateTokenTypes.DOC_STRING_DELIMITER)) {
                state = STATE_NORMAL;
                return;
            }

            // Embedded expressions #(...) within docstrings
            if (tryMatch(remaining, EMBEDDED_EXPR, KarateTokenTypes.EMBEDDED_EXPRESSION)) return;

            // Everything else in docstring is just docstring content (one color)
            // Match until we see """ or #( (embedded expression start)
            int endPos = 0;
            String str = remaining.toString();
            while (endPos < str.length()) {
                // Check for docstring end
                if (str.substring(endPos).startsWith("\"\"\"")) {
                    break;
                }
                // Check for embedded expression start
                if (str.substring(endPos).startsWith("#(")) {
                    break;
                }
                endPos++;
            }
            if (endPos > 0) {
                tokenEnd = tokenStart + endPos;
                tokenType = KarateTokenTypes.DOC_STRING;
                return;
            }
            // Fallback single character
            tokenEnd = tokenStart + 1;
            tokenType = KarateTokenTypes.DOC_STRING;
            return;
        }

        // Whitespace and newlines first
        if (tryMatch(remaining, NEWLINE, KarateTokenTypes.NEWLINE)) return;
        if (tryMatch(remaining, WHITESPACE, KarateTokenTypes.WHITESPACE)) return;

        // Comments (but not match types that start with #)
        if (tryMatch(remaining, COMMENT, KarateTokenTypes.COMMENT)) return;

        // Doc string delimiter
        if (tryMatch(remaining, DOC_STRING_DELIM, KarateTokenTypes.DOC_STRING_DELIMITER)) {
            state = STATE_DOC_STRING;
            return;
        }

        // Tags
        if (tryMatch(remaining, TAG, KarateTokenTypes.TAG)) return;

        // Gherkin structure keywords
        if (tryMatch(remaining, FEATURE, KarateTokenTypes.FEATURE_KEYWORD)) return;
        if (tryMatch(remaining, SCENARIO_OUTLINE, KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD)) return;
        if (tryMatch(remaining, SCENARIO, KarateTokenTypes.SCENARIO_KEYWORD)) return;
        if (tryMatch(remaining, BACKGROUND, KarateTokenTypes.BACKGROUND_KEYWORD)) return;
        if (tryMatch(remaining, EXAMPLES, KarateTokenTypes.EXAMPLES_KEYWORD)) return;

        // Step keywords (Given, When, Then, And, But, *)
        if (tryMatch(remaining, STEP, KarateTokenTypes.STEP_KEYWORD)) return;

        // JavaScript function keyword (before general Karate keywords)
        if (tryMatch(remaining, FUNCTION_KEYWORD, KarateTokenTypes.FUNCTION_KEYWORD)) return;

        // Karate keywords (def, set, match, url, etc.) - must be before other patterns
        if (tryMatch(remaining, KARATE_KEYWORD_PATTERN, KarateTokenTypes.KARATE_KEYWORD)) return;

        // HTTP methods (GET, POST, etc.)
        if (tryMatch(remaining, HTTP_METHOD_PATTERN, KarateTokenTypes.HTTP_METHOD)) return;

        // Java fully-qualified class names (java.util.UUID, etc.)
        if (tryMatch(remaining, JAVA_CLASS, KarateTokenTypes.JAVA_CLASS)) return;

        // Table pipes
        if (tryMatch(remaining, TABLE_PIPE, KarateTokenTypes.PIPE)) return;

        // Match type markers (#ignore, #notnull, etc.)
        if (tryMatch(remaining, MATCH_TYPE, KarateTokenTypes.MATCH_TYPE)) return;

        // Embedded expressions #(...)
        if (tryMatch(remaining, EMBEDDED_EXPR, KarateTokenTypes.EMBEDDED_EXPRESSION)) return;

        // Placeholder variables <...>
        if (tryMatch(remaining, PLACEHOLDER, KarateTokenTypes.VARIABLE)) return;

        // Strings
        if (tryMatch(remaining, SINGLE_STRING, KarateTokenTypes.STRING)) return;
        if (tryMatch(remaining, DOUBLE_STRING, KarateTokenTypes.STRING)) return;

        // JSON literals (before numbers to catch true/false/null)
        if (tryMatch(remaining, JSON_BOOLEAN, KarateTokenTypes.JSON_BOOLEAN)) return;
        if (tryMatch(remaining, JSON_NULL, KarateTokenTypes.JSON_NULL)) return;

        // Numbers
        if (tryMatch(remaining, NUMBER, KarateTokenTypes.NUMBER)) return;

        // Method calls (.methodName before open paren)
        if (tryMatch(remaining, METHOD_CALL, KarateTokenTypes.METHOD_CALL)) return;

        // Arrow function operator
        if (tryMatch(remaining, ARROW_FUNCTION, KarateTokenTypes.ARROW_FUNCTION)) return;

        // Operators
        if (tryMatch(remaining, OPERATOR, KarateTokenTypes.OPERATOR)) return;

        // Structure characters
        if (tryMatch(remaining, BRACE, KarateTokenTypes.BRACE)) return;
        if (tryMatch(remaining, BRACKET, KarateTokenTypes.BRACKET)) return;
        if (tryMatch(remaining, COLON, KarateTokenTypes.COLON)) return;
        if (tryMatch(remaining, COMMA, KarateTokenTypes.COMMA)) return;

        // Other words as plain text
        if (tryMatch(remaining, WORD, KarateTokenTypes.TEXT)) return;

        // Default: single character as text
        tokenEnd = tokenStart + 1;
        tokenType = KarateTokenTypes.TEXT;
    }

    private boolean tryMatch(CharSequence text, Pattern pattern, IElementType type) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find() && matcher.start() == 0) {
            tokenEnd = tokenStart + matcher.end();
            tokenType = type;
            return true;
        }
        return false;
    }

    private int indexOf(CharSequence text, char c) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private int indexOf(CharSequence text, String s) {
        String str = text.toString();
        return str.indexOf(s);
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return bufferEnd;
    }
}

