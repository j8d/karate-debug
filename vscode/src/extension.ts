import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { KarateDebugAdapterFactory } from './debugAdapter';
import { MatchDiagnosticsProvider } from './matchDiagnostics';
import { LicenseManager } from './licensing';
import { KarateDocumentLinkProvider } from './documentLinks';
import { registerJsInlineValuesProvider } from './jsInlineValues';
import { AnalyticsTracker } from './analyticsTracker';

// Global output channel for logging
export let outputChannel: vscode.OutputChannel;

// Default Karate debug configuration
const DEFAULT_KARATE_CONFIG: vscode.DebugConfiguration = {
    type: 'karate',
    request: 'launch',
    name: 'Karate: Debug',
    feature: '${file}',
    enableJavaDebugging: true,
    enableJsDebugging: true,
    skipJdkClasses: true,
    skipKarateFramework: true,
    skipKarateDependencies: true
};

/**
 * Migrates old Karate launch configurations to the new default configuration.
 * Removes any existing configurations with "Karate" in the name and adds the new default.
 */
async function migrateLaunchConfigurations(): Promise<void> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
        return;
    }

    for (const folder of workspaceFolders) {
        const launchConfig = vscode.workspace.getConfiguration('launch', folder.uri);
        const configurations = launchConfig.get<vscode.DebugConfiguration[]>('configurations', []);

        // Check if we have any old Karate configurations (with "Karate" in name but not matching new default)
        const hasOldKarateConfigs = configurations.some(c =>
            c.name?.toLowerCase().includes('karate') && c.name !== 'Karate: Debug'
        );

        // Check if we already have the new default configuration
        const hasNewDefault = configurations.some(c => c.name === 'Karate: Debug');

        if (!hasOldKarateConfigs && hasNewDefault) {
            // Already migrated
            continue;
        }

        if (hasOldKarateConfigs || !hasNewDefault) {
            // Filter out old Karate configurations (anything with "Karate" in name)
            const filteredConfigs = configurations.filter(c =>
                !c.name?.toLowerCase().includes('karate')
            );

            // Add the new default configuration at the beginning
            const newConfigs = [DEFAULT_KARATE_CONFIG, ...filteredConfigs];

            try {
                await launchConfig.update('configurations', newConfigs, vscode.ConfigurationTarget.WorkspaceFolder);
                outputChannel.appendLine(`Migrated launch.json in ${folder.name}: removed old Karate configs, added new default`);
            } catch (error) {
                outputChannel.appendLine(`Failed to migrate launch.json in ${folder.name}: ${error}`);
            }
        }
    }
}

// Global environment state
let currentEnvironment = 'dev';
let environmentStatusBar: vscode.StatusBarItem;
let logLevelStatusBar: vscode.StatusBarItem;

// License manager
let licenseManager: LicenseManager;

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
    outputChannel = vscode.window.createOutputChannel('Karate Debug');
    outputChannel.appendLine('Karate Debug extension activated');

    // Migrate old launch configurations to new default
    migrateLaunchConfigurations();

    // Initialize license manager and check trial status
    // Trial now starts automatically - no login required
    licenseManager = new LicenseManager(context);
    licenseManager.initialize().then(async status => {
        outputChannel.appendLine(`License status: ${status.status}, days remaining: ${status.daysRemaining}`);

        if (status.status === 'expired') {
            // Trial/subscription expired - prompt to purchase
            await licenseManager.showTrialExpiredMessage();
        }
        // Note: 'none' status only happens if offline on first install
        // In that case, features still work and we'll sync when online
    });

    // Register license commands
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.login', () => licenseManager.login()),
        vscode.commands.registerCommand('karateDebug.logout', () => licenseManager.logout()),
        vscode.commands.registerCommand('karateDebug.upgrade', () => licenseManager.startCheckout()),
        vscode.commands.registerCommand('karateDebug.manageSubscription', () => licenseManager.openSubscriptionPortal()),
        vscode.commands.registerCommand('karateDebug.showLicenseInfo', () => showLicenseInfo()),
        // Test command to reset pricing notification flag (for testing only)
        vscode.commands.registerCommand('karateDebug.testResetPricingNotification', async () => {
            await context.globalState.update('hasShownPricingNotification_v0.7.6', undefined);
            vscode.window.showInformationMessage('Pricing notification flag cleared. Reload window to test notification.');
        })
    );

    // Load saved environment (fall back to default from settings)
    const config = vscode.workspace.getConfiguration('karateDebug');
    const defaultEnv = config.get<string>('defaultEnvironment', 'dev');
    currentEnvironment = context.workspaceState.get('karateEnv', defaultEnv);

    // Initialize analytics tracker for session and lifecycle tracking
    const currentVersion = context.extension.packageJSON.version as string;
    const analyticsTracker = new AnalyticsTracker(
        licenseManager.machineIdentifier,
        currentVersion
    );

    // Track lifecycle events (fire-and-forget)
    // Send 'initialized' on every activation to track active installs
    analyticsTracker.trackLifecycleEvent('initialized').catch(() => {});

    // Additionally track version updates for adoption metrics
    const lastVersionKey = 'karate-debug.lastVersion';
    const lastVersion = context.globalState.get<string>(lastVersionKey);
    if (lastVersion && lastVersion !== currentVersion) {
        analyticsTracker.trackLifecycleEvent('updated', lastVersion).catch(() => {});
    }
    context.globalState.update(lastVersionKey, currentVersion);

    // Register debug adapter factory
    const factory = new KarateDebugAdapterFactory(context, outputChannel, analyticsTracker);
    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory('karate', factory)
    );
    context.subscriptions.push(outputChannel);

    // Initialize match diagnostics provider
    const matchDiagnostics = new MatchDiagnosticsProvider(context);
    context.subscriptions.push({ dispose: () => matchDiagnostics.dispose() });

    // Register JavaScript inline values provider for debugging
    registerJsInlineValuesProvider(context);

    // Create environment status bar item (right side, near license status which is priority 50)
    environmentStatusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 52);
    environmentStatusBar.command = 'karateDebug.selectEnvironment';
    updateEnvironmentStatusBar();
    environmentStatusBar.show();
    context.subscriptions.push(environmentStatusBar);

    // Register select environment command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.selectEnvironment', async () => {
            const config = vscode.workspace.getConfiguration('karateDebug');
            const environments = config.get<string[]>('environments', ['dev', 'qa', 'stage']);
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

    // Create log level status bar item (right side, next to environment - higher priority = more left)
    logLevelStatusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 51);
    logLevelStatusBar.command = 'karateDebug.selectLogLevel';
    updateLogLevelStatusBar();
    logLevelStatusBar.show();
    context.subscriptions.push(logLevelStatusBar);

    // Register select log level command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.selectLogLevel', async () => {
            const logLevels = ['error', 'warn', 'info', 'debug', 'trace'];
            const config = vscode.workspace.getConfiguration('karateDebug');
            const currentLevel = config.get<string>('logLevel', 'info');
            const selected = await vscode.window.showQuickPick(logLevels, {
                placeHolder: `Current: ${currentLevel}`,
                title: 'Karate Log Level'
            });
            if (selected) {
                await config.update('logLevel', selected, vscode.ConfigurationTarget.Global);
                updateLogLevelStatusBar();
                vscode.window.showInformationMessage(`Karate log level set to: ${selected}`);
            }
        })
    );

    // Register debug command (with line number argument from CodeLens or tree view)
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.debugFeature', async (arg?: vscode.Uri | FeatureItem, line?: number) => {
            // Check trial status before allowing debug
            if (!licenseManager.isTrialValid()) {
                // Trial expired - prompt to purchase (no login required for trial)
                await licenseManager.showTrialExpiredMessage();
                return;
            }

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

            // Open the feature file before starting debug session
            const uri = vscode.Uri.file(filePath);
            const editor = await vscode.window.showTextDocument(uri);

            // If we have a specific line, scroll to it
            if (scenarioLine > 0) {
                const position = new vscode.Position(scenarioLine, 0);
                editor.selection = new vscode.Selection(position, position);
                editor.revealRange(new vscode.Range(position, position), vscode.TextEditorRevealType.InCenter);
            }

            await debugKarateFeature(uri, scenarioLine);
        })
    );

    // Register debug entire feature command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.debugEntireFeature', async (arg?: vscode.Uri | FeatureItem) => {
            // Check trial status before allowing debug
            if (!licenseManager.isTrialValid()) {
                // Trial expired - prompt to purchase (no login required for trial)
                await licenseManager.showTrialExpiredMessage();
                return;
            }

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

            // Open the feature file before starting debug session
            const uri = vscode.Uri.file(filePath);
            await vscode.window.showTextDocument(uri);

            await debugKarateFeature(uri, -1);
        })
    );

    // Register Feature Explorer tree view
    const featureExplorerProvider = new FeatureExplorerProvider();
    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('karateFeatures', featureExplorerProvider)
    );

    // Register refresh command for Feature Explorer
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.refreshFeatures', () => featureExplorerProvider.refresh())
    );

    // Register open settings command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.openSettings', () => {
            vscode.commands.executeCommand('workbench.action.openSettings', 'karateDebug');
        })
    );

    // Register open feature command
    context.subscriptions.push(
        vscode.commands.registerCommand('karateDebug.openFeature', (filePath: string, line?: number) => {
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

    // Register DocumentLink provider for file hyperlinks
    context.subscriptions.push(
        vscode.languages.registerDocumentLinkProvider(
            { language: 'karate', scheme: 'file' },
            new KarateDocumentLinkProvider()
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
    environmentStatusBar.text = `env:${currentEnvironment}`;
    environmentStatusBar.tooltip = 'Click to change Karate environment';
}

function updateLogLevelStatusBar() {
    const config = vscode.workspace.getConfiguration('karateDebug');
    const logLevel = config.get<string>('logLevel', 'info');
    logLevelStatusBar.text = `log:${logLevel}`;
    logLevelStatusBar.tooltip = 'Click to change Karate log level';
}

export function getCurrentEnvironment(): string {
    return currentEnvironment;
}

async function showLicenseInfo(): Promise<void> {
    const status = licenseManager.getStatus();

    if (status.status === 'none') {
        // Offline on first install - just inform
        const action = await vscode.window.showInformationMessage(
            'Karate Debug: Unable to verify trial. Please check your internet connection.',
            'Contact Developer'
        );
        if (action === 'Contact Developer') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.karatedebug.com/?contact=general&ide=vscode'));
        }
    } else if (status.status === 'active') {
        const action = await vscode.window.showInformationMessage(
            `Karate Debug Pro - Licensed to ${status.githubUsername}`,
            'Manage Subscription',
            'Contact Developer'
        );
        if (action === 'Manage Subscription') {
            await licenseManager.openSubscriptionPortal();
        } else if (action === 'Contact Developer') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.karatedebug.com/?contact=general&ide=vscode'));
        }
    } else if (status.status === 'trialing') {
        const action = await vscode.window.showInformationMessage(
            `Karate Debug Trial: ${status.daysRemaining} days remaining`,
            'Purchase License',
            'Contact Developer'
        );
        if (action === 'Purchase License') {
            await licenseManager.startCheckout();
        } else if (action === 'Contact Developer') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.karatedebug.com/?contact=general&ide=vscode'));
        }
    } else {
        const action = await vscode.window.showWarningMessage(
            'Karate Debug: Trial expired. Purchase a license or sign in if you already have one.',
            'Purchase License',
            'Sign In',
            'Contact Developer'
        );
        if (action === 'Purchase License') {
            await licenseManager.startCheckout();
        } else if (action === 'Sign In') {
            await licenseManager.login();
        } else if (action === 'Contact Developer') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.karatedebug.com/?contact=general&ide=vscode'));
        }
    }
}

async function debugKarateFeature(uri: vscode.Uri, line: number) {
    const workspaceFolder = vscode.workspace.getWorkspaceFolder(uri);
    if (!workspaceFolder) {
        vscode.window.showErrorMessage('No workspace folder found');
        return;
    }

    // line -1 means run entire feature, line >= 0 means specific line (0-indexed, add 1 for Karate)
    const lineSpec = line >= 0 ? `:${line + 1}` : '';
    const featurePath = `${uri.fsPath}${lineSpec}`;

    // Get Karate debug configurations from launch.json
    const launchConfig = vscode.workspace.getConfiguration('launch', workspaceFolder.uri);
    const configurations = launchConfig.get<vscode.DebugConfiguration[]>('configurations', []);
    const karateConfigs = configurations.filter(c => c.type === 'karate');

    let selectedConfig: vscode.DebugConfiguration;

    if (karateConfigs.length === 0) {
        // No Karate configurations - use a minimal default
        const config = vscode.workspace.getConfiguration('karateDebug');
        const logBreakpoints = config.get<string[]>('logBreakpoints', []);
        selectedConfig = {
            type: 'karate',
            request: 'launch',
            name: 'Karate Debug',
            feature: featurePath,
            karateEnv: currentEnvironment,
            logBreakpoints: logBreakpoints
        };
    } else if (karateConfigs.length === 1) {
        // Single configuration - use it
        selectedConfig = { ...karateConfigs[0] };
    } else {
        // Multiple configurations - let user pick
        const picked = await vscode.window.showQuickPick(
            karateConfigs.map(c => ({ label: c.name, config: c })),
            { placeHolder: 'Select debug configuration' }
        );
        if (!picked) {
            return; // User cancelled
        }
        selectedConfig = { ...picked.config };
    }

    // Override the feature path with the current file/line
    selectedConfig.feature = featurePath;

    // Also apply current environment if not explicitly set in config
    if (!selectedConfig.karateEnv) {
        selectedConfig.karateEnv = currentEnvironment;
    }

    // Prevent Debug Console from stealing focus - use Output tab instead
    if (!selectedConfig.internalConsoleOptions) {
        selectedConfig.internalConsoleOptions = 'neverOpen';
    }

    await vscode.debug.startDebugging(workspaceFolder, selectedConfig);
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
                    command: 'karateDebug.debugEntireFeature',
                    arguments: [document.uri]
                }));
            }

            // Add Debug button above each Scenario/Scenario Outline
            if (/^Scenario(\s+Outline)?:/i.test(line)) {
                const range = new vscode.Range(i, 0, i, lines[i].length);
                codeLenses.push(new vscode.CodeLens(range, {
                    title: '▶ Debug Scenario',
                    command: 'karateDebug.debugFeature',
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
                command: 'karateDebug.openFeature',
                title: 'Open Feature',
                arguments: [element.filePath]
            };
            treeItem.contextValue = 'feature';
        } else if (element.type === 'scenario') {
            treeItem.iconPath = new vscode.ThemeIcon('play');
            treeItem.command = {
                command: 'karateDebug.openFeature',
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
