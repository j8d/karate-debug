import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { KarateDebugAdapterFactory } from './debugAdapter';

// Global output channel for logging
export let outputChannel: vscode.OutputChannel;

// Global environment state
let currentEnvironment = 'dev';
let environmentStatusBar: vscode.StatusBarItem;

// Tree item types for Feature Explorer
type FeatureItemType = 'folder' | 'feature' | 'scenario';

interface FeatureItem {
    type: FeatureItemType;
    name: string;
    filePath: string;
    line?: number;
    children?: FeatureItem[];
}

export function activate(context: vscode.ExtensionContext) {
    // Create output channel
    outputChannel = vscode.window.createOutputChannel('Karate Runner');
    outputChannel.appendLine('Karate Runner extension activated');

    // Load saved environment
    currentEnvironment = context.workspaceState.get('karateEnv', 'dev');

    // Register debug adapter factory
    const factory = new KarateDebugAdapterFactory(context, outputChannel);
    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory('karate', factory)
    );
    context.subscriptions.push(outputChannel);

    // Create environment status bar item
    environmentStatusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
    environmentStatusBar.command = 'karateRunner.selectEnvironment';
    updateEnvironmentStatusBar();
    environmentStatusBar.show();
    context.subscriptions.push(environmentStatusBar);

    // Register select environment command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateRunner.selectEnvironment', async () => {
            const environments = ['dev', 'qa', 'stage'];
            const selected = await vscode.window.showQuickPick(environments, {
                placeHolder: 'Select Karate environment',
                title: 'Karate Environment'
            });
            if (selected) {
                currentEnvironment = selected;
                context.workspaceState.update('karateEnv', selected);
                updateEnvironmentStatusBar();
                vscode.window.showInformationMessage(`Karate environment set to: ${selected}`);
            }
        })
    );

    // Register debug command (with line number argument from CodeLens or tree view)
    context.subscriptions.push(
        vscode.commands.registerCommand('karateRunner.debugFeature', async (arg?: vscode.Uri | FeatureItem, line?: number) => {
            let filePath: string;
            let scenarioLine: number;

            if (!arg) {
                // Called from command palette
                const editor = vscode.window.activeTextEditor;
                if (!editor || !editor.document.fileName.endsWith('.feature')) {
                    vscode.window.showErrorMessage('Please open a .feature file to debug');
                    return;
                }
                filePath = editor.document.uri.fsPath;
                scenarioLine = editor.selection.active.line;
            } else if (arg instanceof vscode.Uri) {
                // Called from CodeLens
                filePath = arg.fsPath;
                scenarioLine = line ?? 0;
            } else {
                // Called from tree view (FeatureItem)
                filePath = arg.filePath;
                scenarioLine = arg.line ?? 0;
            }

            await debugKarateFeature(vscode.Uri.file(filePath), scenarioLine);
        })
    );

    // Register debug entire feature command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateRunner.debugEntireFeature', async (arg?: vscode.Uri | FeatureItem) => {
            let filePath: string;

            if (!arg) {
                // Called from command palette
                const editor = vscode.window.activeTextEditor;
                if (!editor || !editor.document.fileName.endsWith('.feature')) {
                    vscode.window.showErrorMessage('Please open a .feature file to debug');
                    return;
                }
                filePath = editor.document.uri.fsPath;
            } else if (arg instanceof vscode.Uri) {
                // Called from CodeLens
                filePath = arg.fsPath;
            } else {
                // Called from tree view (FeatureItem)
                filePath = arg.filePath;
            }

            await debugKarateFeature(vscode.Uri.file(filePath), -1);
        })
    );

    // Register Feature Explorer tree view
    const featureExplorerProvider = new FeatureExplorerProvider();
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('karateFeatures', featureExplorerProvider)
    );

    // Register refresh command for Feature Explorer
    context.subscriptions.push(
        vscode.commands.registerCommand('karateRunner.refreshFeatures', () => featureExplorerProvider.refresh())
    );

    // Register open feature command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateRunner.openFeature', (filePath: string, line?: number) => {
            const uri = vscode.Uri.file(filePath);
            vscode.window.showTextDocument(uri).then(editor => {
                if (line !== undefined && line >= 0) {
                    const position = new vscode.Position(line, 0);
                    editor.selection = new vscode.Selection(position, position);
                    editor.revealRange(new vscode.Range(position, position));
                }
            });
        })
    );

    // Register CodeLens provider
    context.subscriptions.push(
        vscode.languages.registerCodeLensProvider(
            { language: 'karate', scheme: 'file' },
            new KarateCodeLensProvider()
        )
    );

    // Watch for feature file changes
    const watcher = vscode.workspace.createFileSystemWatcher('**/*.feature');
    watcher.onDidCreate(() => featureExplorerProvider.refresh());
    watcher.onDidDelete(() => featureExplorerProvider.refresh());
    watcher.onDidChange(() => featureExplorerProvider.refresh());
    context.subscriptions.push(watcher);
}

function updateEnvironmentStatusBar() {
    environmentStatusBar.text = `Karate: ${currentEnvironment}`;
    environmentStatusBar.tooltip = 'Click to change Karate environment';
}

export function getCurrentEnvironment(): string {
    return currentEnvironment;
}

async function debugKarateFeature(uri: vscode.Uri, line: number) {
    const workspaceFolder = vscode.workspace.getWorkspaceFolder(uri);
    if (!workspaceFolder) {
        vscode.window.showErrorMessage('No workspace folder found');
        return;
    }

    // line -1 means run entire feature, line >= 0 means specific line (0-indexed, add 1 for Karate)
    const lineSpec = line >= 0 ? `:${line + 1}` : '';

    await vscode.debug.startDebugging(workspaceFolder, {
        type: 'karate',
        request: 'launch',
        name: 'Karate Debug',
        feature: `${uri.fsPath}${lineSpec}`,
        karateEnv: currentEnvironment
    });
}

class KarateCodeLensProvider implements vscode.CodeLensProvider {
    provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
        const codeLenses: vscode.CodeLens[] = [];
        const text = document.getText();
        const lines = text.split('\n');

        for (let i = 0; i < lines.length; i++) {
            // Remove BOM, leading/trailing whitespace
            const line = lines[i].replace(/^\uFEFF/, '').trim();

            // Add Debug button above Feature to run entire feature
            if (/^Feature:/i.test(line)) {
                const range = new vscode.Range(i, 0, i, lines[i].length);
                codeLenses.push(new vscode.CodeLens(range, {
                    title: '▶ Debug Feature',
                    command: 'karateRunner.debugEntireFeature',
                    arguments: [document.uri]
                }));
            }

            // Add Debug button above each Scenario/Scenario Outline
            if (/^Scenario(\s+Outline)?:/i.test(line)) {
                const range = new vscode.Range(i, 0, i, lines[i].length);
                codeLenses.push(new vscode.CodeLens(range, {
                    title: '▶ Debug Scenario',
                    command: 'karateRunner.debugFeature',
                    arguments: [document.uri, i]
                }));
            }
        }
        return codeLenses;
    }
}

export function deactivate() {}

class FeatureExplorerProvider implements vscode.TreeDataProvider<FeatureItem> {
    private _onDidChangeTreeData = new vscode.EventEmitter<FeatureItem | undefined>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

    refresh(): void {
        this._onDidChangeTreeData.fire(undefined);
    }

    getTreeItem(element: FeatureItem): vscode.TreeItem {
        const treeItem = new vscode.TreeItem(
            element.name,
            element.type === 'scenario'
                ? vscode.TreeItemCollapsibleState.None
                : vscode.TreeItemCollapsibleState.Collapsed
        );

        if (element.type === 'folder') {
            treeItem.iconPath = new vscode.ThemeIcon('folder');
        } else if (element.type === 'feature') {
            treeItem.iconPath = new vscode.ThemeIcon('file');
            treeItem.command = {
                command: 'karateRunner.openFeature',
                title: 'Open Feature',
                arguments: [element.filePath]
            };
            treeItem.contextValue = 'feature';
        } else if (element.type === 'scenario') {
            treeItem.iconPath = new vscode.ThemeIcon('play');
            treeItem.command = {
                command: 'karateRunner.openFeature',
                title: 'Open Scenario',
                arguments: [element.filePath, element.line]
            };
            treeItem.contextValue = 'scenario';
        }

        return treeItem;
    }

    async getChildren(element?: FeatureItem): Promise<FeatureItem[]> {
        if (!vscode.workspace.workspaceFolders) {
            return [];
        }

        if (!element) {
            // Root level - find all feature folders
            return this.findFeatureFolders();
        }

        if (element.type === 'folder') {
            // Return features in this folder
            return this.findFeaturesInFolder(element.filePath);
        }

        if (element.type === 'feature') {
            // Return scenarios in this feature
            return this.parseFeatureFile(element.filePath);
        }

        return [];
    }

    private async findFeatureFolders(): Promise<FeatureItem[]> {
        const items: FeatureItem[] = [];
        const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
        if (!workspaceFolder) return items;

        const testJavaPath = path.join(workspaceFolder.uri.fsPath, 'src', 'test', 'java');

        if (fs.existsSync(testJavaPath)) {
            const entries = fs.readdirSync(testJavaPath, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.isDirectory()) {
                    const folderPath = path.join(testJavaPath, entry.name);
                    // Check if folder contains .feature files
                    if (this.folderContainsFeatures(folderPath)) {
                        items.push({
                            type: 'folder',
                            name: entry.name,
                            filePath: folderPath
                        });
                    }
                }
            }
        }

        return items;
    }

    private folderContainsFeatures(folderPath: string): boolean {
        try {
            const entries = fs.readdirSync(folderPath, { withFileTypes: true });
            for (const entry of entries) {
                if (entry.isFile() && entry.name.endsWith('.feature')) {
                    return true;
                }
                if (entry.isDirectory()) {
                    // Recursively check subdirectories
                    if (this.folderContainsFeatures(path.join(folderPath, entry.name))) {
                        return true;
                    }
                }
            }
            return false;
        } catch {
            return false;
        }
    }

    private findFeaturesInFolder(folderPath: string): FeatureItem[] {
        const items: FeatureItem[] = [];
        try {
            const entries = fs.readdirSync(folderPath, { withFileTypes: true });
            for (const entry of entries) {
                const entryPath = path.join(folderPath, entry.name);
                if (entry.isDirectory()) {
                    // Add subdirectories that contain features
                    if (this.folderContainsFeatures(entryPath)) {
                        items.push({
                            type: 'folder',
                            name: entry.name,
                            filePath: entryPath
                        });
                    }
                } else if (entry.isFile() && entry.name.endsWith('.feature')) {
                    items.push({
                        type: 'feature',
                        name: entry.name,
                        filePath: entryPath
                    });
                }
            }
        } catch {
            // Ignore errors
        }
        return items;
    }

    private parseFeatureFile(filePath: string): FeatureItem[] {
        const items: FeatureItem[] = [];
        try {
            const content = fs.readFileSync(filePath, 'utf8');
            const lines = content.split('\n');

            for (let i = 0; i < lines.length; i++) {
                const line = lines[i].trim();
                if (line.startsWith('Scenario:') || line.startsWith('Scenario Outline:')) {
                    const name = line.replace(/^(Scenario:|Scenario Outline:)\s*/, '');
                    items.push({
                        type: 'scenario',
                        name: name || `Scenario at line ${i + 1}`,
                        filePath: filePath,
                        line: i
                    });
                }
            }
        } catch {
            // Ignore errors
        }
        return items;
    }
}
