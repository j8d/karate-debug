/**
 * GraalVM Chrome Inspector Client
 * 
 * This module connects to GraalVM's Chrome DevTools Protocol (CDP) inspector
 * to enable JavaScript debugging within Karate tests.
 * 
 * CDP Protocol Basics:
 * - Communication is via WebSocket with JSON messages
 * - We send commands like { id: 1, method: "Debugger.enable", params: {} }
 * - We receive events like { method: "Debugger.scriptParsed", params: { scriptId, url, ... } }
 * 
 * Key CDP methods we use:
 * - Debugger.enable: Start receiving debugger events
 * - Debugger.scriptParsed: Fired when a script is loaded
 * - Debugger.paused: Fired when execution pauses (breakpoint, step, etc.)
 */

import * as path from 'path';
import * as vscode from 'vscode';
import WebSocket from 'ws';

export interface ScriptInfo {
    scriptId: string;
    url: string;
    startLine: number;
    startColumn: number;
    endLine: number;
    endColumn: number;
    sourceMapURL?: string;
}

export class GraalInspectorClient {
    private ws: WebSocket | null = null;
    private messageId = 0;
    private outputChannel: vscode.OutputChannel;
    private workspaceRoot: string;
    private scripts: Map<string, ScriptInfo> = new Map();
    private pendingResponses: Map<number, { resolve: (value: unknown) => void; reject: (error: Error) => void; timeoutId: NodeJS.Timeout }> = new Map();
    private static readonly REQUEST_TIMEOUT_MS = 30000; // 30 second timeout for CDP requests

    // Breakpoint synchronization
    private fileToScriptId: Map<string, string> = new Map();  // file path -> script ID
    private scriptIdToFile: Map<string, string> = new Map();  // script ID -> file path
    private breakpointListener: vscode.Disposable | null = null;
    private pauseDecoration: vscode.TextEditorDecorationType | null = null;
    private isPausedOnBreakpoint = false;

    // Suspend=true handling: We may receive a pause before we've matched scripts to files
    // We need to track whether we're in the initial suspended state and resume after setting breakpoints
    private isInitialSuspend = true;
    private pendingInitialResume = false;
    private configScriptMatched = false;

    constructor(outputChannel: vscode.OutputChannel, workspaceRoot: string) {
        this.outputChannel = outputChannel;
        this.workspaceRoot = workspaceRoot;

        // Create decoration for showing paused line
        this.pauseDecoration = vscode.window.createTextEditorDecorationType({
            backgroundColor: new vscode.ThemeColor('editor.stackFrameHighlightBackground'),
            isWholeLine: true
        });
    }
    
    /**
     * Connect to GraalVM's inspector WebSocket
     * @param port The inspector port (e.g., 9229)
     */
    async connect(port: number): Promise<void> {
        // First, we need to get the WebSocket URL from the inspector
        // GraalVM exposes a JSON endpoint that tells us the exact WebSocket path
        const infoUrl = `http://127.0.0.1:${port}/json`;
        
        try {
            this.log(`Fetching inspector info from ${infoUrl}`);
            
            const response = await fetch(infoUrl);
            const targets = await response.json() as Array<{ webSocketDebuggerUrl: string; title: string }>;
            
            if (!targets || targets.length === 0) {
                throw new Error('No debug targets found');
            }
            
            const wsUrl = targets[0].webSocketDebuggerUrl;
            this.log(`Connecting to WebSocket: ${wsUrl}`);
            
            await this.connectWebSocket(wsUrl);
            
        } catch (error) {
            this.log(`Failed to connect: ${error}`);
            throw error;
        }
    }
    
    private connectWebSocket(wsUrl: string): Promise<void> {
        return new Promise((resolve, reject) => {
            this.ws = new WebSocket(wsUrl);

            this.ws.on('open', async () => {
                this.log('WebSocket connected');

                // Enable the debugger to start receiving events
                // With Suspend=true + WaitAttached=true, GraalVM is waiting for us
                await this.sendCommand('Debugger.enable');
                await this.sendCommand('Runtime.enable');

                this.log('Inspector ready - GraalVM should be suspended, waiting for scripts');

                // Start listening for breakpoint changes immediately
                this.startBreakpointListener();

                // Give a moment for any pending events to arrive
                // Then check if we need to handle initial state
                setTimeout(() => {
                    this.log(`[STATUS] Scripts received: ${this.scripts.size}, configMatched: ${this.configScriptMatched}`);
                }, 500);

                resolve();
            });
            
            this.ws.on('message', (data) => {
                const msg = JSON.parse(data.toString());
                // Log all incoming messages for debugging
                if (msg.method) {
                    this.log(`[WS MSG] method=${msg.method}`);
                } else if (msg.id !== undefined) {
                    this.log(`[WS MSG] response id=${msg.id}`);
                }
                this.handleMessage(msg);
            });

            this.ws.on('error', (error) => {
                this.log(`WebSocket error: ${error}`);
                reject(error);
            });

            this.ws.on('close', (code, reason) => {
                this.log(`WebSocket closed: code=${code} reason=${reason?.toString() || 'none'}`);
            });
        });
    }
    
    private async sendCommand(method: string, params: Record<string, unknown> = {}): Promise<void> {
        if (!this.ws) {
            throw new Error('Not connected');
        }

        const id = ++this.messageId;
        const message = { id, method, params };

        this.log(`Sending: ${method}`);
        this.ws.send(JSON.stringify(message));
    }

    private sendCommandWithResponse(method: string, params: Record<string, unknown> = {}): Promise<unknown> {
        return new Promise((resolve, reject) => {
            if (!this.ws) {
                reject(new Error('Not connected'));
                return;
            }

            const id = ++this.messageId;
            const message = { id, method, params };

            // Set up timeout to prevent promise leaks if target never responds
            const timeoutId = setTimeout(() => {
                const pending = this.pendingResponses.get(id);
                if (pending) {
                    this.pendingResponses.delete(id);
                    pending.reject(new Error(`CDP request '${method}' timed out after ${GraalInspectorClient.REQUEST_TIMEOUT_MS}ms`));
                }
            }, GraalInspectorClient.REQUEST_TIMEOUT_MS);

            // Store the promise callbacks to resolve when we get the response
            this.pendingResponses.set(id, { resolve, reject, timeoutId });

            this.ws.send(JSON.stringify(message));
        });
    }

    private handleMessage(message: { method?: string; params?: Record<string, unknown>; id?: number; result?: unknown; error?: unknown }): void {
        // Handle command responses
        if (message.id !== undefined) {
            const pending = this.pendingResponses.get(message.id);
            if (pending) {
                clearTimeout(pending.timeoutId); // Clear the timeout since we got a response
                this.pendingResponses.delete(message.id);
                if (message.error) {
                    pending.reject(new Error(JSON.stringify(message.error)));
                } else {
                    pending.resolve(message.result);
                }
            }
        }

        if (message.method) {
            this.handleEvent(message.method, message.params || {});
        }
    }
    
    private handleEvent(method: string, params: Record<string, unknown>): void {
        switch (method) {
            case 'Debugger.scriptParsed':
                this.onScriptParsed(params as unknown as ScriptInfo);
                break;
            case 'Debugger.paused':
                this.onPaused(params);
                break;
            case 'Debugger.breakpointResolved':
                this.log(`[BREAKPOINT RESOLVED] ${JSON.stringify(params)}`);
                break;
            case 'Debugger.resumed':
                this.log('[DEBUGGER RESUMED]');
                break;
            default:
                // Log other events for debugging
                this.log(`Event: ${method}`);
        }
    }
    
    private onScriptParsed(script: ScriptInfo): void {
        this.scripts.set(script.scriptId, script);

        // Log full script info - this helps us understand what GraalVM reports
        this.log(`[SCRIPT] id=${script.scriptId} url="${script.url}" lines=${script.startLine}-${script.endLine}`);

        // For multi-line scripts (likely user files), fetch the source to identify them
        const lineCount = script.endLine - script.startLine;
        if (lineCount > 3) {
            this.fetchScriptSource(script);
        }
    }

    private async fetchScriptSource(script: ScriptInfo): Promise<void> {
        try {
            const response = await this.sendCommandWithResponse('Debugger.getScriptSource', {
                scriptId: script.scriptId
            });

            if (response && typeof response === 'object' && 'scriptSource' in response) {
                const source = (response as { scriptSource: string }).scriptSource;
                const preview = source.substring(0, 100).replace(/\n/g, '\\n');
                this.log(`[SOURCE id=${script.scriptId}] ${preview}...`);

                // Try to match this source to a file on disk
                this.tryMatchSourceToFile(script, source);
            }
        } catch (error) {
            this.log(`[SOURCE ERROR] Failed to get source for script ${script.scriptId}: ${error}`);
        }
    }

    private async tryMatchSourceToFile(script: ScriptInfo, source: string): Promise<void> {
        // Check if this looks like karate-config.js
        if (source.includes('karate.env') || source.includes('function fn()')) {
            const configPath = path.join(this.workspaceRoot, 'src', 'test', 'java', 'karate-config.js');

            try {
                const fileContent = await vscode.workspace.fs.readFile(vscode.Uri.file(configPath));
                const fileSource = Buffer.from(fileContent).toString('utf8');

                // Karate wraps the config in parentheses: (function fn() {...})
                // So we need to check if the source contains the file content
                const normalizedSource = source.replace(/^\(/, '').replace(/\)$/, '').trim();
                const normalizedFile = fileSource.trim();

                if (normalizedSource === normalizedFile || source.includes(normalizedFile)) {
                    this.log(`[MATCHED] Script ${script.scriptId} is karate-config.js`);

                    // Register the mapping
                    this.fileToScriptId.set(configPath, script.scriptId);
                    this.scriptIdToFile.set(script.scriptId, configPath);

                    // Open the file in VS Code
                    const doc = await vscode.workspace.openTextDocument(configPath);
                    await vscode.window.showTextDocument(doc, { preview: false });

                    // Sync any existing breakpoints in this file
                    await this.syncBreakpointsForFile(configPath, script.scriptId);

                    // Start listening for breakpoint changes
                    this.startBreakpointListener();

                    // Mark that we've matched the config script
                    this.configScriptMatched = true;

                    // If we were waiting to resume (initial suspend), do it now
                    if (this.pendingInitialResume) {
                        this.log('[MATCHED] Resuming after initial breakpoint setup');
                        this.pendingInitialResume = false;
                        this.isInitialSuspend = false;
                        await this.sendCommand('Debugger.resume');
                        this.log('[RESUMED] Execution started with breakpoints set');
                    }
                } else {
                    this.log(`[NO MATCH] Script ${script.scriptId} source differs from karate-config.js`);
                }
            } catch (err) {
                this.log(`[MATCH ERROR] Could not read karate-config.js: ${err}`);
            }
        }
    }

    /**
     * Set breakpoints before scripts are parsed (for Suspend=true mode)
     * Since GraalVM reports scripts as "Unnamed", we try to set breakpoints
     * that will apply when the first script is loaded
     */
    private async setPreParseBreakpoints(): Promise<void> {
        const configPath = path.join(this.workspaceRoot, 'src', 'test', 'java', 'karate-config.js');

        // Get breakpoints for karate-config.js
        const breakpoints = vscode.debug.breakpoints.filter(
            bp => bp instanceof vscode.SourceBreakpoint && bp.location.uri.fsPath === configPath
        ) as vscode.SourceBreakpoint[];

        if (breakpoints.length === 0) {
            this.log('[PRE-PARSE] No breakpoints in karate-config.js');
            return;
        }

        this.log(`[PRE-PARSE] Setting ${breakpoints.length} breakpoints for karate-config.js`);

        // Try setting breakpoints by URL regex pattern
        // GraalVM uses "Unnamed" for inline scripts, so we try to match that
        for (const bp of breakpoints) {
            if (bp.enabled) {
                const line = bp.location.range.start.line;
                try {
                    // Try with urlRegex matching "Unnamed" scripts
                    const response = await this.sendCommandWithResponse('Debugger.setBreakpointByUrl', {
                        lineNumber: line,
                        urlRegex: '.*Unnamed.*'
                    });
                    this.log(`[PRE-PARSE] Set breakpoint at line ${line + 1} (urlRegex): ${JSON.stringify(response)}`);
                } catch (error) {
                    this.log(`[PRE-PARSE] Failed to set breakpoint by URL: ${error}`);
                }
            }
        }

        // Also register the file mapping so we can track it later
        this.fileToScriptId.set(configPath, 'pending');
    }

    /**
     * Sync VS Code breakpoints to GraalVM for a specific file
     */
    private async syncBreakpointsForFile(filePath: string, scriptId: string): Promise<void> {
        const uri = vscode.Uri.file(filePath);
        const breakpoints = vscode.debug.breakpoints.filter(
            bp => bp instanceof vscode.SourceBreakpoint && bp.location.uri.fsPath === filePath
        ) as vscode.SourceBreakpoint[];

        this.log(`[SYNC] Found ${breakpoints.length} breakpoints in ${path.basename(filePath)}`);

        for (const bp of breakpoints) {
            if (bp.enabled) {
                await this.setBreakpointInGraalVM(scriptId, bp.location.range.start.line);
            }
        }
    }

    /**
     * Set a breakpoint in GraalVM via CDP
     */
    private async setBreakpointInGraalVM(scriptId: string, line: number): Promise<void> {
        try {
            // CDP uses 0-based line numbers, VS Code uses 0-based too
            const response = await this.sendCommandWithResponse('Debugger.setBreakpoint', {
                location: {
                    scriptId: scriptId,
                    lineNumber: line
                }
            });
            this.log(`[BREAKPOINT SET] Script ${scriptId}, line ${line + 1}`);
        } catch (error) {
            this.log(`[BREAKPOINT ERROR] Failed to set breakpoint: ${error}`);
        }
    }

    /**
     * Remove a breakpoint in GraalVM via CDP
     */
    private async removeBreakpointInGraalVM(breakpointId: string): Promise<void> {
        try {
            await this.sendCommandWithResponse('Debugger.removeBreakpoint', {
                breakpointId: breakpointId
            });
            this.log(`[BREAKPOINT REMOVED] ${breakpointId}`);
        } catch (error) {
            this.log(`[BREAKPOINT ERROR] Failed to remove breakpoint: ${error}`);
        }
    }

    /**
     * Start listening for VS Code breakpoint changes
     */
    private startBreakpointListener(): void {
        if (this.breakpointListener) {
            return; // Already listening
        }

        this.breakpointListener = vscode.debug.onDidChangeBreakpoints(async (event) => {
            // Handle added breakpoints
            for (const bp of event.added) {
                if (bp instanceof vscode.SourceBreakpoint) {
                    const filePath = bp.location.uri.fsPath;
                    const scriptId = this.fileToScriptId.get(filePath);
                    if (scriptId && bp.enabled) {
                        await this.setBreakpointInGraalVM(scriptId, bp.location.range.start.line);
                    }
                }
            }

            // Handle removed breakpoints - we'd need to track breakpoint IDs for this
            // For now, just log
            for (const bp of event.removed) {
                if (bp instanceof vscode.SourceBreakpoint) {
                    this.log(`[BREAKPOINT] Removed breakpoint at ${bp.location.uri.fsPath}:${bp.location.range.start.line + 1}`);
                }
            }
        });

        this.log('[BREAKPOINT LISTENER] Started watching for breakpoint changes');
    }

    private async onPaused(params: Record<string, unknown>): Promise<void> {
        const reason = params.reason as string;
        this.log(`[PAUSED] reason=${reason}`);

        // Handle initial suspend from Suspend=true mode
        // In this case, we want to wait for script matching and breakpoint setup before resuming
        if (this.isInitialSuspend) {
            this.log('[INITIAL SUSPEND] GraalVM is paused waiting for debugger');

            // If we've already matched the config script, we can resume now
            if (this.configScriptMatched) {
                this.log('[INITIAL SUSPEND] Config script already matched, resuming');
                this.isInitialSuspend = false;
                await this.sendCommand('Debugger.resume');
                this.log('[RESUMED] After initial breakpoint setup');
            } else {
                // We haven't matched the script yet, mark that we need to resume later
                this.log('[INITIAL SUSPEND] Waiting for script matching before resuming');
                this.pendingInitialResume = true;
            }
            return;
        }

        // Check if this is a breakpoint hit
        if (reason === 'breakpoint' || reason === 'other') {
            const callFrames = params.callFrames as Array<{
                location: { scriptId: string; lineNumber: number; columnNumber: number };
                functionName: string;
            }>;

            if (callFrames && callFrames.length > 0) {
                const topFrame = callFrames[0];
                const scriptId = topFrame.location.scriptId;
                const line = topFrame.location.lineNumber;

                // Check if this is a file we're tracking
                const filePath = this.scriptIdToFile.get(scriptId);
                if (filePath) {
                    this.log(`[PAUSED AT] ${path.basename(filePath)}:${line + 1}`);
                    this.isPausedOnBreakpoint = true;

                    // Highlight the line in VS Code
                    await this.highlightPausedLine(filePath, line);

                    // Don't auto-resume - let user control
                    return;
                }
            }
        }

        // Auto-resume for non-breakpoint pauses (or pauses in files we don't track)
        await this.sendCommand('Debugger.resume');
        this.log('[RESUMED] Auto-resumed');
    }
    
    /**
     * Highlight the paused line in VS Code
     */
    private async highlightPausedLine(filePath: string, line: number): Promise<void> {
        try {
            const uri = vscode.Uri.file(filePath);
            const doc = await vscode.workspace.openTextDocument(uri);
            const editor = await vscode.window.showTextDocument(doc, { preview: false });

            // Move cursor to the paused line
            const position = new vscode.Position(line, 0);
            editor.selection = new vscode.Selection(position, position);
            editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);

            // Apply highlight decoration
            if (this.pauseDecoration) {
                const range = new vscode.Range(line, 0, line, Number.MAX_VALUE);
                editor.setDecorations(this.pauseDecoration, [range]);
            }

            // Show a message with continue/step options
            const action = await vscode.window.showInformationMessage(
                `Paused at ${path.basename(filePath)}:${line + 1}`,
                'Continue',
                'Step Over'
            );

            // Clear the highlight
            if (this.pauseDecoration) {
                editor.setDecorations(this.pauseDecoration, []);
            }

            if (action === 'Continue') {
                await this.sendCommand('Debugger.resume');
                this.log('[RESUMED] User clicked Continue');
            } else if (action === 'Step Over') {
                await this.sendCommand('Debugger.stepOver');
                this.log('[STEP OVER] User clicked Step Over');
            } else {
                // User dismissed - resume anyway
                await this.sendCommand('Debugger.resume');
                this.log('[RESUMED] Dialog dismissed');
            }

            this.isPausedOnBreakpoint = false;
        } catch (error) {
            this.log(`[HIGHLIGHT ERROR] ${error}`);
            // Resume on error
            await this.sendCommand('Debugger.resume');
        }
    }

    private log(message: string): void {
        this.outputChannel.appendLine(`[GraalInspector] ${message}`);
    }

    disconnect(): void {
        // Clean up breakpoint listener
        if (this.breakpointListener) {
            this.breakpointListener.dispose();
            this.breakpointListener = null;
        }

        // Clean up decoration
        if (this.pauseDecoration) {
            this.pauseDecoration.dispose();
            this.pauseDecoration = null;
        }

        // Reject all pending responses to prevent promise leaks
        for (const [, pending] of this.pendingResponses) {
            clearTimeout(pending.timeoutId);
            pending.reject(new Error('Disconnected'));
        }
        this.pendingResponses.clear();

        // Close WebSocket
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }

        // Clear all mappings and script metadata
        this.scripts.clear();
        this.fileToScriptId.clear();
        this.scriptIdToFile.clear();
    }
}

