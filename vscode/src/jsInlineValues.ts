import * as vscode from 'vscode';

/**
 * Provides inline values for JavaScript files during debugging.
 * When debugging stops in a JS file, this provider scans the visible code
 * for variable names and tells VS Code to look them up in the current stack frame.
 */
export class JavaScriptInlineValuesProvider implements vscode.InlineValuesProvider {
    
    // Regex to match JavaScript identifiers (variable names)
    // Matches: word characters that don't start with a digit
    private static readonly IDENTIFIER_REGEX = /\b([a-zA-Z_$][a-zA-Z0-9_$]*)\b/g;
    
    // JavaScript reserved words and built-ins to skip
    private static readonly SKIP_WORDS = new Set([
        // Keywords
        'break', 'case', 'catch', 'continue', 'debugger', 'default', 'delete',
        'do', 'else', 'finally', 'for', 'function', 'if', 'in', 'instanceof',
        'new', 'return', 'switch', 'this', 'throw', 'try', 'typeof', 'var',
        'void', 'while', 'with', 'class', 'const', 'enum', 'export', 'extends',
        'import', 'super', 'implements', 'interface', 'let', 'package', 'private',
        'protected', 'public', 'static', 'yield', 'async', 'await', 'of',
        // Literals
        'true', 'false', 'null', 'undefined', 'NaN', 'Infinity',
        // Common built-ins
        'console', 'Math', 'JSON', 'Object', 'Array', 'String', 'Number',
        'Boolean', 'Date', 'RegExp', 'Error', 'Map', 'Set', 'Promise',
        'Symbol', 'BigInt', 'Function', 'eval', 'parseInt', 'parseFloat',
        'isNaN', 'isFinite', 'decodeURI', 'decodeURIComponent', 'encodeURI',
        'encodeURIComponent', 'arguments',
        // Karate built-ins
        'karate', 'read', 'call', 'callonce', 'get', 'set', 'remove', 'match',
        'print', 'assert', 'configure', 'table', 'text', 'replace', 'csv',
        'yaml', 'xmlPath', 'jsonPath', 'lowerCase', 'upperCase', 'trim',
        'length', 'keys', 'values', 'entries', 'merge', 'append', 'copy',
        'range', 'repeat', 'sort', 'reverse', 'distinct', 'flatten', 'chunk'
    ]);

    provideInlineValues(
        document: vscode.TextDocument,
        viewPort: vscode.Range,
        context: vscode.InlineValueContext,
        token: vscode.CancellationToken
    ): vscode.ProviderResult<vscode.InlineValue[]> {
        
        const inlineValues: vscode.InlineValue[] = [];
        const seenVariables = new Set<string>();
        
        // Scan lines from the top of the viewport to the stopped location
        const endLine = Math.min(context.stoppedLocation.end.line, viewPort.end.line);
        
        for (let lineNum = viewPort.start.line; lineNum <= endLine; lineNum++) {
            if (token.isCancellationRequested) {
                break;
            }
            
            const line = document.lineAt(lineNum);
            const lineText = line.text;
            
            // Skip empty lines and comments
            const trimmed = lineText.trim();
            if (!trimmed || trimmed.startsWith('//') || trimmed.startsWith('/*') || trimmed.startsWith('*')) {
                continue;
            }
            
            // Find all identifiers on this line
            let match;
            JavaScriptInlineValuesProvider.IDENTIFIER_REGEX.lastIndex = 0;
            
            while ((match = JavaScriptInlineValuesProvider.IDENTIFIER_REGEX.exec(lineText)) !== null) {
                const varName = match[1];
                
                // Skip reserved words and already-seen variables
                if (JavaScriptInlineValuesProvider.SKIP_WORDS.has(varName) || seenVariables.has(varName)) {
                    continue;
                }
                
                // Skip if it looks like a function call (followed by open paren)
                const afterMatch = lineText.substring(match.index + varName.length);
                if (afterMatch.trimStart().startsWith('(')) {
                    continue;
                }
                
                // Skip if it's a property access (preceded by dot)
                const beforeMatch = lineText.substring(0, match.index);
                if (beforeMatch.trimEnd().endsWith('.')) {
                    continue;
                }
                
                seenVariables.add(varName);
                
                // Create range for this variable occurrence
                const range = new vscode.Range(
                    lineNum, match.index,
                    lineNum, match.index + varName.length
                );
                
                // Use InlineValueVariableLookup - VS Code will look up the variable value
                inlineValues.push(new vscode.InlineValueVariableLookup(range, varName, false));
            }
        }
        
        return inlineValues;
    }
}

/**
 * Register the JavaScript inline values provider.
 * Call this from extension.ts during activation.
 */
export function registerJsInlineValuesProvider(context: vscode.ExtensionContext): void {
    const provider = new JavaScriptInlineValuesProvider();
    
    // Register for JavaScript files
    context.subscriptions.push(
        vscode.languages.registerInlineValuesProvider(
            { language: 'javascript', scheme: 'file' },
            provider
        )
    );
    
    // Also register for TypeScript in case any TS files are debugged
    context.subscriptions.push(
        vscode.languages.registerInlineValuesProvider(
            { language: 'typescript', scheme: 'file' },
            provider
        )
    );
}

