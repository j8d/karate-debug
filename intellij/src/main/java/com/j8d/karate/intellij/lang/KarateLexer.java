package com.j8d.karate.intellij.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexer for Karate feature files.
 * Tokenizes the file into keywords, steps, strings, etc.
 */
public class KarateLexer extends LexerBase {
    
    private CharSequence buffer;
    private int bufferEnd;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    
    // Patterns for Karate syntax
    private static final Pattern FEATURE = Pattern.compile("^\\s*Feature:");
    private static final Pattern SCENARIO = Pattern.compile("^\\s*Scenario:");
    private static final Pattern SCENARIO_OUTLINE = Pattern.compile("^\\s*Scenario Outline:");
    private static final Pattern BACKGROUND = Pattern.compile("^\\s*Background:");
    private static final Pattern EXAMPLES = Pattern.compile("^\\s*Examples:");
    private static final Pattern STEP = Pattern.compile("^\\s*(Given|When|Then|And|But|\\*)\\s");
    private static final Pattern TAG = Pattern.compile("^\\s*@[\\w-]+");
    private static final Pattern COMMENT = Pattern.compile("^\\s*#(?!\\().*");
    private static final Pattern DOC_STRING = Pattern.compile("^\\s*\"\"\"");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|");
    private static final Pattern STRING = Pattern.compile("'[^']*'|\"[^\"]*\"");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\.?\\d*\\b");
    private static final Pattern WHITESPACE = Pattern.compile("^[ \\t]+");
    private static final Pattern NEWLINE = Pattern.compile("^[\\r\\n]+");
    
    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        advance();
    }
    
    @Override
    public int getState() {
        return 0;
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
        String line = getLineFromPosition(remaining);
        
        // Try to match patterns in order of priority
        if (tryMatch(remaining, NEWLINE, KarateTokenTypes.NEWLINE)) return;
        if (tryMatch(remaining, WHITESPACE, KarateTokenTypes.WHITESPACE)) return;
        if (tryMatch(remaining, COMMENT, KarateTokenTypes.COMMENT)) return;
        if (tryMatch(remaining, TAG, KarateTokenTypes.TAG)) return;
        if (tryMatch(remaining, FEATURE, KarateTokenTypes.FEATURE_KEYWORD)) return;
        if (tryMatch(remaining, SCENARIO_OUTLINE, KarateTokenTypes.SCENARIO_OUTLINE_KEYWORD)) return;
        if (tryMatch(remaining, SCENARIO, KarateTokenTypes.SCENARIO_KEYWORD)) return;
        if (tryMatch(remaining, BACKGROUND, KarateTokenTypes.BACKGROUND_KEYWORD)) return;
        if (tryMatch(remaining, EXAMPLES, KarateTokenTypes.EXAMPLES_KEYWORD)) return;
        if (tryMatch(remaining, STEP, KarateTokenTypes.STEP_KEYWORD)) return;
        if (tryMatch(remaining, TABLE_ROW, KarateTokenTypes.PIPE)) return;
        
        // Default: consume as text until end of line or next token
        int nextNewline = indexOf(remaining, '\n');
        if (nextNewline == -1) {
            tokenEnd = bufferEnd;
        } else {
            tokenEnd = tokenStart + nextNewline;
        }
        if (tokenEnd == tokenStart) {
            tokenEnd = tokenStart + 1;
        }
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
    
    private String getLineFromPosition(CharSequence text) {
        int newline = indexOf(text, '\n');
        if (newline == -1) {
            return text.toString();
        }
        return text.subSequence(0, newline).toString();
    }
    
    private int indexOf(CharSequence text, char c) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                return i;
            }
        }
        return -1;
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

