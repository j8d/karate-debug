import * as vscode from 'vscode';

// Store match failure info for quick fixes and inlay hints
interface MatchFailureInfo {
    actualValue: string;
    actualType?: string;  // The detected type of the actual value (for type matchers)
    expectedValue: string;
    expectedRange: vscode.Range;
    lineNumber: number;
    isQuoted: boolean;  // Whether the value is a quoted string
    isTypeMatcher: boolean;  // Whether the expected value is a type matcher like #array
    isErrorMessage: boolean;  // True if this is an error (syntax/type) not a value mismatch
}

// Map of document URI + line number to failure info
const matchFailures = new Map<string, MatchFailureInfo>();

// Event emitter to trigger inlay hint refresh
const onDidChangeMatchFailures = new vscode.EventEmitter<void>();

/**
 * Command handler to accept an actual value and replace the expected value.
 */
async function acceptActualValue(uri: string, lineNumber: number): Promise<void> {
    const key = `${uri}:${lineNumber}`;
    const failureInfo = matchFailures.get(key);

    if (!failureInfo) {
        vscode.window.showWarningMessage('No match failure info found for this line');
        return;
    }

    let replacement: string;

    if (failureInfo.isTypeMatcher && failureInfo.actualType) {
        // For type matchers, replace with the correct type matcher
        replacement = typeToMatcher(failureInfo.actualType);
    } else {
        // Format the replacement value based on whether it should be quoted
        replacement = failureInfo.isQuoted
            ? `'${failureInfo.actualValue}'`
            : failureInfo.actualValue;
    }

    const edit = new vscode.WorkspaceEdit();
    edit.replace(
        vscode.Uri.parse(uri),
        failureInfo.expectedRange,
        replacement
    );

    await vscode.workspace.applyEdit(edit);

    // Remove the failure info and refresh
    matchFailures.delete(key);
    onDidChangeMatchFailures.fire();
}

/**
 * Convert a detected type to its Karate type matcher.
 */
function typeToMatcher(type: string): string {
    switch (type) {
        case 'string':
            return "'#string'";
        case 'number':
        case 'number (decimal)':
            return "'#number'";
        case 'boolean':
            return "'#boolean'";
        case 'array':
            return "'#array'";
        case 'object':
            return "'#object'";
        case 'null':
            return "'#null'";
        default:
            return `'#${type}'`;
    }
}

/**
 * Provides real-time match statement diagnostics while debugging.
 * Shows green underlines for passing matches and red for failing.
 */
export class MatchDiagnosticsProvider {
    private passingDecorationType: vscode.TextEditorDecorationType;
    private failingDecorationType: vscode.TextEditorDecorationType;
    private diagnosticCollection: vscode.DiagnosticCollection;
    private documentChangeListener: vscode.Disposable | undefined;
    private debounceTimer: NodeJS.Timeout | undefined;
    private activeSession: vscode.DebugSession | undefined;
    private isPaused: boolean = false;

    // Regex to match Karate match statements
    private static readonly MATCH_REGEX = /^\s*(\*|Given|When|Then|And|But)\s+match\s+(.+)$/;

    // Regex to parse failure message - handles both quoted and unquoted values
    // Format: "  $ | not equal (type)\n  actual\n  expected" or with quoted values
    // Also handles type mismatch messages like "not an array or list", "not a string", etc.
    private static readonly FAILURE_VALUE_REGEX = /not equal \([^)]+\)\n\s+'([^']+)'\n\s+'([^']+)'/;
    private static readonly FAILURE_VALUE_UNQUOTED_REGEX = /not equal \(([^)]+)\)\n\s*(\S+)\n\s*(\S+)/;
    // Regex for type mismatch failures (e.g., "not an array or list", "not a string")
    // Handles quoted values: not a string (STRING:STRING)\n'actual'\n'#string'
    private static readonly FAILURE_TYPE_MISMATCH_REGEX = /not (?:an? )?([^(]+) \([^)]+\)\n\s+'([^']+)'\n\s+'([^']+)'/;
    // Handles unquoted actual values (arrays, objects): not a string (LIST:STRING)\n[...]\n'#string'
    private static readonly FAILURE_TYPE_MISMATCH_UNQUOTED_REGEX = /not (?:an? )?([^(]+) \([^)]+\)\n\s*(\[.+\]|\{.+\})\n\s*'(#\w+)'/;

    constructor(private context: vscode.ExtensionContext) {
        // Create decoration types with visible styling
        this.passingDecorationType = vscode.window.createTextEditorDecorationType({
            textDecoration: 'underline dotted rgba(50, 205, 50, 0.85)',
            overviewRulerColor: 'rgba(50, 205, 50, 0.7)',
            overviewRulerLane: vscode.OverviewRulerLane.Right
        });

        this.failingDecorationType = vscode.window.createTextEditorDecorationType({
            textDecoration: 'underline dotted rgba(255, 80, 80, 0.85)',
            overviewRulerColor: 'rgba(255, 80, 80, 0.7)',
            overviewRulerLane: vscode.OverviewRulerLane.Right
        });

        // Create diagnostic collection for Code Actions
        this.diagnosticCollection = vscode.languages.createDiagnosticCollection('karateMatch');
        context.subscriptions.push(this.diagnosticCollection);

        // Register Code Action provider
        context.subscriptions.push(
            vscode.languages.registerCodeActionsProvider(
                { language: 'karate' },
                new MatchQuickFixProvider(),
                { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] }
            )
        );

        // Register Inlay Hints provider for inline actual values
        context.subscriptions.push(
            vscode.languages.registerInlayHintsProvider(
                { language: 'karate' },
                new MatchInlayHintsProvider()
            )
        );

        // Register command to accept actual value
        context.subscriptions.push(
            vscode.commands.registerCommand('karateDebug.acceptActualValue', acceptActualValue)
        );

        // Listen for debug session events
        context.subscriptions.push(
            vscode.debug.onDidStartDebugSession(session => this.onDebugSessionStarted(session))
        );
        context.subscriptions.push(
            vscode.debug.onDidTerminateDebugSession(session => this.onDebugSessionEnded(session))
        );

        // Track when debugger stops (via active stack item changes)
        context.subscriptions.push(
            vscode.debug.onDidChangeActiveStackItem(item => this.onActiveStackItemChanged(item))
        );
    }

    /** Returns true if any match diagnostics feature is enabled */
    private isEnabled(): boolean {
        const config = vscode.workspace.getConfiguration('karateDebug');
        const showPassing = config.get<boolean>('matchDiagnostics.showPassing', true);
        const showFailing = config.get<boolean>('matchDiagnostics.showFailing', true);
        const showActualValues = config.get<boolean>('matchDiagnostics.showActualValues', true);
        return showPassing || showFailing || showActualValues;
    }

    private onDebugSessionStarted(session: vscode.DebugSession): void {
        if (session.type === 'karate') {
            this.activeSession = session;
        }
    }

    private onDebugSessionEnded(session: vscode.DebugSession): void {
        if (session === this.activeSession) {
            this.activeSession = undefined;
            this.isPaused = false;
            this.clearDecorations();
            this.stopDocumentListener();
        }
    }

    private onActiveStackItemChanged(item: vscode.DebugStackFrame | vscode.DebugThread | undefined): void {
        // When a stack frame becomes active, the debugger has stopped
        if (item && this.activeSession) {
            this.isPaused = true;
            this.startDocumentListener();
            // Delay evaluation slightly to ensure pause state is fully established
            if (this.isEnabled()) {
                setTimeout(() => {
                    if (this.isPaused && this.isEnabled()) {
                        this.evaluateMatchStatements();
                    }
                }, 100);
            }
        } else if (!item && this.activeSession) {
            // Stack cleared means execution resumed
            this.isPaused = false;
            this.clearDecorations();
            this.stopDocumentListener();
        }
    }

    private startDocumentListener(): void {
        if (this.documentChangeListener) return;

        this.documentChangeListener = vscode.workspace.onDidChangeTextDocument(e => {
            if (e.document.languageId === 'karate' && this.isEnabled() && this.isPaused) {
                this.debounceEvaluate();
            }
        });
    }

    private stopDocumentListener(): void {
        if (this.documentChangeListener) {
            this.documentChangeListener.dispose();
            this.documentChangeListener = undefined;
        }
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
            this.debounceTimer = undefined;
        }
    }

    private debounceEvaluate(): void {
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
        }
        this.debounceTimer = setTimeout(() => this.evaluateMatchStatements(), 300);
    }

    private async evaluateMatchStatements(): Promise<void> {
        if (!this.activeSession || !this.isPaused || !this.isEnabled()) return;

        const editor = vscode.window.activeTextEditor;
        if (!editor || editor.document.languageId !== 'karate') return;

        const config = vscode.workspace.getConfiguration('karateDebug');
        const showPassing = config.get<boolean>('matchDiagnostics.showPassing', true);
        const showFailing = config.get<boolean>('matchDiagnostics.showFailing', true);
        const showActualValues = config.get<boolean>('matchDiagnostics.showActualValues', true);

        const passingRanges: vscode.DecorationOptions[] = [];
        const failingRanges: vscode.DecorationOptions[] = [];
        const diagnostics: vscode.Diagnostic[] = [];

        // Clear previous failure info for this document
        const docUri = editor.document.uri.toString();
        for (const key of matchFailures.keys()) {
            if (key.startsWith(docUri + ':')) {
                matchFailures.delete(key);
            }
        }

        const document = editor.document;
        const lineCount = document.lineCount;

        for (let i = 0; i < lineCount; i++) {
            const line = document.lineAt(i);
            const match = MatchDiagnosticsProvider.MATCH_REGEX.exec(line.text);

            if (match) {
                const matchExpression = match[2].trim();
                const result = await this.evaluateMatch(matchExpression);

                // Skip unavailable results (variables not yet defined)
                if (result.status === 'unavailable') {
                    continue;
                }

                if (result.status === 'pass' && showPassing) {
                    passingRanges.push({
                        range: new vscode.Range(i, line.firstNonWhitespaceCharacterIndex, i, line.text.length),
                        hoverMessage: 'Match PASSED'
                    });
                } else if ((result.status === 'fail' || result.status === 'error') && (showFailing || showActualValues)) {
                    const lineRange = new vscode.Range(i, line.firstNonWhitespaceCharacterIndex, i, line.text.length);
                    const isError = result.status === 'error';
                    let hoverMessage = isError ? `Match ERROR: ${result.message}` : `Match FAILED: ${result.message}`;

                    if (isError) {
                        // For errors, store directly without parsing (no [Fix] available)
                        const key = `${docUri}:${i}`;
                        matchFailures.set(key, {
                            actualValue: result.message,
                            expectedValue: '',
                            expectedRange: lineRange,
                            lineNumber: i,
                            isQuoted: false,
                            isTypeMatcher: false,
                            isErrorMessage: true
                        });
                    } else {
                        // Try to parse the failure message to extract actual and expected values
                        const failureInfo = this.parseFailureMessage(result.message, line.text, i, docUri);

                        if (failureInfo) {
                            matchFailures.set(failureInfo.key, failureInfo.info);
                            hoverMessage = failureInfo.hoverMessage;

                            // Create diagnostic for Code Actions (use Hint severity to avoid duplicate squiggly underline)
                            const diagnostic = new vscode.Diagnostic(
                                lineRange,
                                hoverMessage,
                                vscode.DiagnosticSeverity.Hint
                            );
                            diagnostic.source = 'Karate Debug';
                            diagnostic.code = 'match-failed';
                            diagnostics.push(diagnostic);
                        }
                    }

                    // Only add decoration if showFailing is enabled
                    if (showFailing) {
                        failingRanges.push({
                            range: lineRange,
                            hoverMessage
                        });
                    }
                }
            }
        }

        editor.setDecorations(this.passingDecorationType, passingRanges);
        editor.setDecorations(this.failingDecorationType, failingRanges);
        this.diagnosticCollection.set(editor.document.uri, diagnostics);

        // Trigger inlay hints refresh
        onDidChangeMatchFailures.fire();

        // Force inlay hints to refresh by toggling the setting
        this.forceInlayHintsRefresh();
    }

    /**
     * Parse a failure message to extract actual and expected values.
     * Handles both quoted strings and unquoted values (numbers, booleans, schema matchers).
     */
    private parseFailureMessage(
        message: string,
        lineText: string,
        lineNumber: number,
        docUri: string
    ): { key: string; info: MatchFailureInfo; hoverMessage: string } | null {
        console.log(`[parseFailureMessage] message: ${JSON.stringify(message)}`);
        console.log(`[parseFailureMessage] lineText: ${lineText}`);

        // Try quoted string format first: not equal (STRING) 'actual' 'expected'
        const quotedMatch = MatchDiagnosticsProvider.FAILURE_VALUE_REGEX.exec(message);
        if (quotedMatch) {
            console.log(`[parseFailureMessage] Quoted match found: actual='${quotedMatch[1]}', expected='${quotedMatch[2]}'`);
            const actualValue = quotedMatch[1];
            const expectedValue = quotedMatch[2];
            const expectedInLine = lineText.indexOf(`'${expectedValue}'`);

            if (expectedInLine !== -1) {
                return {
                    key: `${docUri}:${lineNumber}`,
                    info: {
                        actualValue,
                        expectedValue,
                        expectedRange: new vscode.Range(
                            lineNumber, expectedInLine,
                            lineNumber, expectedInLine + expectedValue.length + 2
                        ),
                        lineNumber,
                        isQuoted: true,
                        isTypeMatcher: false,
                        isErrorMessage: false
                    },
                    hoverMessage: `Match FAILED: Expected '${expectedValue}' but got '${actualValue}'`
                };
            }
        }

        // Try unquoted format: not equal (NUMBER)\nactual\nexpected
        const unquotedMatch = MatchDiagnosticsProvider.FAILURE_VALUE_UNQUOTED_REGEX.exec(message);
        console.log(`[parseFailureMessage] Unquoted regex result: ${JSON.stringify(unquotedMatch)}`);

        if (unquotedMatch) {
            const actualValue = unquotedMatch[2];
            const expectedValue = unquotedMatch[3];
            console.log(`[parseFailureMessage] Unquoted match: actual='${actualValue}', expected='${expectedValue}'`);

            const result = this.findExpectedInLine(lineText, expectedValue, actualValue, lineNumber, docUri);
            if (result) {
                return result;
            }
        }

        // Try type mismatch format: not an array or list (STRING:STRING)\n'actual'\n'#array'
        const typeMismatchMatch = MatchDiagnosticsProvider.FAILURE_TYPE_MISMATCH_REGEX.exec(message);
        console.log(`[parseFailureMessage] Type mismatch regex result: ${JSON.stringify(typeMismatchMatch)}`);

        if (typeMismatchMatch) {
            const mismatchType = typeMismatchMatch[1].trim(); // e.g., "array or list", "string"
            const actualValue = typeMismatchMatch[2];
            const expectedValue = typeMismatchMatch[3]; // e.g., "#array"
            console.log(`[parseFailureMessage] Type mismatch: type='${mismatchType}', actual='${actualValue}', expected='${expectedValue}'`);

            const result = this.findExpectedInLine(lineText, expectedValue, actualValue, lineNumber, docUri, mismatchType);
            if (result) {
                return result;
            }
        }

        // Try unquoted type mismatch format (for arrays/objects): not a string (ARRAY)\n[...]\n#string
        const typeMismatchUnquotedMatch = MatchDiagnosticsProvider.FAILURE_TYPE_MISMATCH_UNQUOTED_REGEX.exec(message);
        console.log(`[parseFailureMessage] Type mismatch unquoted regex result: ${JSON.stringify(typeMismatchUnquotedMatch)}`);

        if (typeMismatchUnquotedMatch) {
            const mismatchType = typeMismatchUnquotedMatch[1].trim(); // e.g., "string", "number"
            const actualValue = typeMismatchUnquotedMatch[2].trim();
            const expectedValue = typeMismatchUnquotedMatch[3]; // e.g., "#string"
            console.log(`[parseFailureMessage] Type mismatch unquoted: type='${mismatchType}', actual='${actualValue}', expected='${expectedValue}'`);

            const result = this.findExpectedInLine(lineText, expectedValue, actualValue, lineNumber, docUri, mismatchType);
            if (result) {
                return result;
            }
        }

        return null;
    }

    /**
     * Find the expected value in the line and create a failure info result.
     */
    private findExpectedInLine(
        lineText: string,
        expectedValue: string,
        actualValue: string,
        lineNumber: number,
        docUri: string,
        mismatchType?: string
    ): { key: string; info: MatchFailureInfo; hoverMessage: string } | null {
        // Find the expected value in the line (look for == expectedValue or == 'expectedValue' or == #matcher)
        const equalsPattern = new RegExp(`==\\s*(${this.escapeRegex(expectedValue)}|'${this.escapeRegex(expectedValue)}'|#\\w+)\\s*$`);
        const equalsMatch = equalsPattern.exec(lineText);
        console.log(`[findExpectedInLine] equalsMatch: ${JSON.stringify(equalsMatch)}`);

        if (equalsMatch) {
            const matchedValue = equalsMatch[1];
            const expectedInLine = lineText.lastIndexOf(matchedValue);
            const isQuoted = matchedValue.startsWith("'");
            // Check if it's a type matcher like #array, '#array', #string, etc.
            const isTypeMatcher = matchedValue.startsWith('#') ||
                (isQuoted && matchedValue.length > 2 && matchedValue[1] === '#');

            if (expectedInLine !== -1) {
                // Detect the type of the actual value for type matchers
                const actualType = isTypeMatcher ? this.detectType(actualValue) : undefined;

                const hoverMessage = mismatchType
                    ? `Match FAILED: Expected ${matchedValue} but got ${actualType || 'value'}: '${actualValue}'`
                    : `Match FAILED: Expected ${matchedValue} but got ${actualValue}`;

                return {
                    key: `${docUri}:${lineNumber}`,
                    info: {
                        actualValue,
                        actualType,
                        expectedValue,
                        expectedRange: new vscode.Range(
                            lineNumber, expectedInLine,
                            lineNumber, expectedInLine + matchedValue.length
                        ),
                        lineNumber,
                        isQuoted,
                        isTypeMatcher,
                        isErrorMessage: false
                    },
                    hoverMessage
                };
            }
        }

        return null;
    }

    /**
     * Detect the type of a value from its string representation.
     */
    private detectType(value: string): string {
        // Check if it's a number
        if (/^-?\d+(\.\d+)?$/.test(value)) {
            return value.includes('.') ? 'number (decimal)' : 'number';
        }
        // Check if it's a boolean
        if (value === 'true' || value === 'false') {
            return 'boolean';
        }
        // Check if it's null
        if (value === 'null') {
            return 'null';
        }
        // Check if it starts with [ - likely an array
        if (value.startsWith('[')) {
            return 'array';
        }
        // Check if it starts with { - likely an object
        if (value.startsWith('{')) {
            return 'object';
        }
        // Default to string
        return 'string';
    }

    private escapeRegex(str: string): string {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    private async evaluateMatch(expression: string): Promise<{ status: 'pass' | 'fail' | 'error' | 'unavailable'; message: string }> {
        if (!this.activeSession) {
            return { status: 'unavailable', message: 'No active debug session' };
        }

        try {
            // Use the debug adapter's evaluate capability
            const response = await this.activeSession.customRequest('evaluate', {
                expression: `match ${expression}`,
                context: 'repl'
            });

            const result = response.result as string;

            // Check for "not defined" errors - variable not yet available
            if (result.includes('is not defined') || result.includes('ReferenceError')) {
                return { status: 'unavailable', message: 'Variable not yet defined' };
            }

            if (result.startsWith('PASS')) {
                return { status: 'pass', message: '' };
            } else if (result.startsWith('FAIL:')) {
                return { status: 'fail', message: result.substring(6).trim() };
            } else if (result.startsWith('Error:') || result.startsWith('Match error:')) {
                // Evaluation errors (syntax errors, type errors, unknown matchers) - show as error
                return { status: 'error', message: this.simplifyErrorMessage(result) };
            } else {
                return { status: 'fail', message: result };
            }
        } catch (error) {
            // Network or debug session errors - treat as unavailable
            return { status: 'unavailable', message: String(error) };
        }
    }

    /**
     * Simplify error messages by adding a friendly prefix for known error patterns.
     */
    private simplifyErrorMessage(message: string): string {
        // Strip existing "Match error: " or "Error: " prefix
        let cleanMessage = message;
        if (cleanMessage.startsWith('Match error: ')) {
            cleanMessage = cleanMessage.substring('Match error: '.length);
        } else if (cleanMessage.startsWith('Error: ')) {
            cleanMessage = cleanMessage.substring('Error: '.length);
        }

        // Syntax errors - JS parsing failures
        if (cleanMessage.includes('js failed') || cleanMessage.includes('SyntaxError') ||
            cleanMessage.includes('PolyglotException')) {
            return 'invalid syntax: ' + cleanMessage;
        }

        // Type cast errors
        if (cleanMessage.includes('cannot be cast to') || cleanMessage.includes('ClassCastException')) {
            return 'invalid type: ' + cleanMessage;
        }

        // Unknown matcher type
        if (cleanMessage.includes('unknown validator')) {
            return 'unknown matcher: ' + cleanMessage;
        }

        return cleanMessage;
    }

    private clearDecorations(): void {
        console.log('[clearDecorations] Clearing decorations and match failures');
        for (const editor of vscode.window.visibleTextEditors) {
            editor.setDecorations(this.passingDecorationType, []);
            editor.setDecorations(this.failingDecorationType, []);
        }
        this.diagnosticCollection.clear();
        matchFailures.clear();
        console.log(`[clearDecorations] matchFailures size after clear: ${matchFailures.size}`);

        // Fire event to refresh inlay hints
        onDidChangeMatchFailures.fire();

        // Workaround: Force inlay hints refresh by toggling the setting
        this.forceInlayHintsRefresh();
    }

    private async forceInlayHintsRefresh(): Promise<void> {
        console.log('[forceInlayHintsRefresh] Starting refresh...');
        try {
            const config = vscode.workspace.getConfiguration('editor');
            const current = config.get<string>('inlayHints.enabled');
            console.log(`[forceInlayHintsRefresh] Current setting: ${current}`);

            // If inlay hints are off, we need to turn them on
            // Toggle off then on to force refresh
            if (current === 'off' || !current) {
                // Turn on inlay hints
                await config.update('inlayHints.enabled', 'on', vscode.ConfigurationTarget.Global);
                console.log('[forceInlayHintsRefresh] Enabled inlay hints (was off)');
            } else {
                // Toggle off then on to force refresh
                await config.update('inlayHints.enabled', 'off', vscode.ConfigurationTarget.Global);
                console.log('[forceInlayHintsRefresh] Set to off');
                await config.update('inlayHints.enabled', current, vscode.ConfigurationTarget.Global);
                console.log(`[forceInlayHintsRefresh] Set back to ${current}`);
            }
        } catch (error) {
            console.error(`[forceInlayHintsRefresh] Error: ${error}`);
        }
    }

    public dispose(): void {
        this.stopDocumentListener();
        this.clearDecorations();
        this.passingDecorationType.dispose();
        this.failingDecorationType.dispose();
        this.diagnosticCollection.dispose();
    }
}

/**
 * Provides quick fix Code Actions for failed match statements.
 */
class MatchQuickFixProvider implements vscode.CodeActionProvider {
    provideCodeActions(
        document: vscode.TextDocument,
        _range: vscode.Range | vscode.Selection,
        context: vscode.CodeActionContext,
        _token: vscode.CancellationToken
    ): vscode.CodeAction[] | undefined {
        const actions: vscode.CodeAction[] = [];

        for (const diagnostic of context.diagnostics) {
            if (diagnostic.code !== 'match-failed') continue;

            const lineNum = diagnostic.range.start.line;
            const key = `${document.uri.toString()}:${lineNum}`;
            const failureInfo = matchFailures.get(key);

            if (failureInfo) {
                const action = new vscode.CodeAction(
                    `Replace '${failureInfo.expectedValue}' with '${failureInfo.actualValue}'`,
                    vscode.CodeActionKind.QuickFix
                );

                action.edit = new vscode.WorkspaceEdit();
                action.edit.replace(
                    document.uri,
                    failureInfo.expectedRange,
                    `'${failureInfo.actualValue}'`
                );

                action.isPreferred = true;
                action.diagnostics = [diagnostic];

                actions.push(action);
            }
        }

        return actions;
    }
}

/**
 * Provides inlay hints showing actual values for failed matches.
 */
class MatchInlayHintsProvider implements vscode.InlayHintsProvider {
    onDidChangeInlayHints = onDidChangeMatchFailures.event;

    provideInlayHints(
        document: vscode.TextDocument,
        range: vscode.Range,
        _token: vscode.CancellationToken
    ): vscode.InlayHint[] {
        const hints: vscode.InlayHint[] = [];
        const docUri = document.uri.toString();

        // Check if actual values display is enabled
        const config = vscode.workspace.getConfiguration('karateDebug');
        const showActualValues = config.get<boolean>('matchDiagnostics.showActualValues', true);
        if (!showActualValues) {
            return hints;
        }

        console.log(`[InlayHints] provideInlayHints called for ${docUri}, matchFailures size: ${matchFailures.size}`);

        try {
        for (const [key, info] of matchFailures.entries()) {
            console.log(`[InlayHints] Checking key: ${key}, info: ${JSON.stringify({...info, expectedRange: undefined})}`);
            if (!key.startsWith(docUri + ':')) continue;

            // Check if this line is in the requested range
            if (info.lineNumber < range.start.line || info.lineNumber > range.end.line) continue;

            const line = document.lineAt(info.lineNumber);
            const position = new vscode.Position(info.lineNumber, line.text.length);

            // Handle error messages differently - no "actual:" prefix, no [Fix] button
            if (info.isErrorMessage) {
                const errorPart = new vscode.InlayHintLabelPart(`  ${info.actualValue}`);
                errorPart.tooltip = new vscode.MarkdownString(`Match error - cannot auto-fix`);

                const hint = new vscode.InlayHint(position, [errorPart]);
                hint.paddingLeft = true;
                hints.push(hint);
                console.log(`[InlayHints] Added error hint for line ${info.lineNumber}`);
                continue;
            }

            // Format values based on whether they're quoted strings or type matchers
            let displayActual: string;
            let displayExpected: string;

            if (info.isTypeMatcher && info.actualType) {
                // For type matchers, show "actual: <type>" instead of the full value
                displayActual = info.actualType;
                displayExpected = info.expectedValue;
            } else {
                displayActual = info.isQuoted ? `'${info.actualValue}'` : info.actualValue;
                displayExpected = info.isQuoted ? `'${info.expectedValue}'` : info.expectedValue;
            }

            // Create the "actual: value" part
            const actualPart = new vscode.InlayHintLabelPart(`  actual: ${displayActual} `);

            // Add tooltip with full value for type matchers
            if (info.isTypeMatcher) {
                actualPart.tooltip = new vscode.MarkdownString(`Full value: \`'${info.actualValue}'\``);
            }

            // Format the replacement value for display
            let replacementValue: string;
            if (info.isTypeMatcher && info.actualType) {
                // For type matchers, show the type matcher that will be used
                replacementValue = typeToMatcher(info.actualType);
            } else {
                replacementValue = info.isQuoted ? `'${info.actualValue}'` : info.actualValue;
            }

            // Create the clickable "Fix" button
            const fixPart = new vscode.InlayHintLabelPart('[Fix]');
            fixPart.tooltip = new vscode.MarkdownString(
                `**Cmd+Click** to replace \`${displayExpected}\` with \`${replacementValue}\``
            );
            fixPart.command = {
                title: 'Fix match value',
                command: 'karateDebug.acceptActualValue',
                arguments: [docUri, info.lineNumber]
            };

            const hint = new vscode.InlayHint(position, [actualPart, fixPart]);
            hint.paddingLeft = true;

            hints.push(hint);
            console.log(`[InlayHints] Added hint for line ${info.lineNumber}`);
        }
        } catch (error) {
            console.error(`[InlayHints] Error creating hints: ${error}`);
        }

        console.log(`[InlayHints] Returning ${hints.length} hints`);
        return hints;
    }
}
