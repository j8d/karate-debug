import * as vscode from 'vscode';
import * as crypto from 'crypto';
import * as os from 'os';
import * as fs from 'fs';
import * as path from 'path';
import { API_BASE_URL, GITHUB_CLIENT_ID } from './config';

const BACKUP_FILE_NAME = '.karate-debug-license';

export interface LicenseStatus {
    isValid: boolean;
    status: 'active' | 'trialing' | 'expired' | 'none';
    daysRemaining?: number;
    expiresAt?: string;
    githubUsername?: string;
    trialStartDate?: Date;
}

interface AnonymousTrialResponse {
    status: 'trialing' | 'expired' | 'converted' | 'none';
    startedAt?: string;
    expiresAt?: string;
    daysRemaining?: number;
    isNew?: boolean;
    convertedUserId?: string;
}

export interface User {
    userId: string;
    githubUsername: string;
    email?: string;
}

interface LocalLicenseData {
    odUserId: string;
    trialStartTimestamp: number;
    machineId: string;
    lastValidated: number;
}

export class LicenseManager {
    private context: vscode.ExtensionContext;
    private machineId: string;
    private clientVersion: string;
    private statusBarItem: vscode.StatusBarItem;
    private currentStatus: LicenseStatus = { isValid: false, status: 'none' };
    private pendingAuthResolve: ((code: string | null) => void) | null = null;

    constructor(context: vscode.ExtensionContext) {
        this.context = context;
        this.machineId = this.getMachineId();
        this.clientVersion = context.extension.packageJSON.version || 'unknown';

        // Create status bar item
        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 50);
        this.statusBarItem.command = 'karateDebug.showLicenseInfo';
        context.subscriptions.push(this.statusBarItem);

        // Register URI handler once during construction
        const uriHandler = vscode.window.registerUriHandler({
            handleUri: (uri: vscode.Uri) => {
                console.log('[Karate Debug] URI handler received:', uri.toString());
                console.log('[Karate Debug] URI path:', uri.path);
                console.log('[Karate Debug] URI query:', uri.query);

                // Accept both /auth-callback and auth-callback (with or without leading slash)
                if (uri.path === '/auth-callback' || uri.path === 'auth-callback') {
                    const code = new URLSearchParams(uri.query).get('code');
                    console.log('[Karate Debug] Auth code received:', code ? 'yes' : 'no');
                    if (this.pendingAuthResolve) {
                        this.pendingAuthResolve(code);
                        this.pendingAuthResolve = null;
                    } else {
                        console.log('[Karate Debug] No pending auth resolve!');
                    }
                }
            }
        });
        context.subscriptions.push(uriHandler);
    }

    private getMachineId(): string {
        const hostname = os.hostname();
        const platform = os.platform();
        const arch = os.arch();
        const cpus = os.cpus()[0]?.model || 'unknown';
        const totalMem = os.totalmem();
        const raw = `karate-debug-${hostname}-${platform}-${arch}-${cpus}-${totalMem}`;
        return crypto.createHash('sha256').update(raw).digest('hex').substring(0, 32);
    }

    // === Local Backup Storage (Anti-Piracy Layer) ===

    private getBackupFilePath(): string {
        return path.join(os.homedir(), BACKUP_FILE_NAME);
    }

    private encrypt(data: string): string {
        const key = this.machineId.substring(0, 16);
        const cipher = crypto.createCipheriv('aes-128-ecb', key, null);
        let encrypted = cipher.update(data, 'utf8', 'base64');
        encrypted += cipher.final('base64');
        return encrypted;
    }

    private decrypt(encrypted: string): string | null {
        try {
            const key = this.machineId.substring(0, 16);
            const decipher = crypto.createDecipheriv('aes-128-ecb', key, null);
            let decrypted = decipher.update(encrypted, 'base64', 'utf8');
            decrypted += decipher.final('utf8');
            return decrypted;
        } catch {
            return null;
        }
    }

    private readLocalBackup(): LocalLicenseData | null {
        try {
            const filePath = this.getBackupFilePath();
            if (!fs.existsSync(filePath)) {
                return null;
            }
            const encrypted = fs.readFileSync(filePath, 'utf8');
            const decrypted = this.decrypt(encrypted);
            if (!decrypted) {
                return null;
            }
            return JSON.parse(decrypted);
        } catch {
            return null;
        }
    }

    private writeLocalBackup(data: LocalLicenseData): void {
        try {
            const filePath = this.getBackupFilePath();
            const encrypted = this.encrypt(JSON.stringify(data));
            fs.writeFileSync(filePath, encrypted, { mode: 0o600 });
        } catch (error) {
            console.error('[LicenseManager] Failed to write backup file:', error);
        }
    }

    /**
     * Get the earliest trial start from both server data and local backup.
     * This prevents users from extending trials by clearing one storage.
     */
    private getEarliestTrialStart(serverTrialStart: number | null): number | null {
        const timestamps: number[] = [];

        if (serverTrialStart) {
            timestamps.push(serverTrialStart);
        }

        const localData = this.readLocalBackup();
        if (localData && localData.machineId === this.machineId) {
            timestamps.push(localData.trialStartTimestamp);
        }

        const globalStateTimestamp = this.context.globalState.get<number>('trialStartTimestamp');
        if (globalStateTimestamp) {
            timestamps.push(globalStateTimestamp);
        }

        return timestamps.length > 0 ? Math.min(...timestamps) : null;
    }

    /**
     * Sync trial start to all storage locations using the earliest timestamp.
     */
    private async syncTrialStart(userId: string, timestamp: number): Promise<void> {
        await this.context.globalState.update('trialStartTimestamp', timestamp);
        this.writeLocalBackup({
            odUserId: userId,
            trialStartTimestamp: timestamp,
            machineId: this.machineId,
            lastValidated: Date.now()
        });
    }


    // === Server-Side Authentication & License Management ===

    async initialize(): Promise<LicenseStatus> {
        const userId = this.context.globalState.get<string>('userId');

        // If user is logged in, validate their subscription
        if (userId) {
            return this.validateLicense();
        }

        // Otherwise, start/check anonymous trial (no auth required)
        return this.startOrCheckAnonymousTrial();
    }

    /**
     * Start or check anonymous machine-based trial.
     * No GitHub auth required - trial starts immediately.
     */
    private async startOrCheckAnonymousTrial(): Promise<LicenseStatus> {
        try {
            const response = await fetch(`${API_BASE_URL}/trial/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    machineId: this.machineId,
                    machineName: os.hostname(),
                    platform: 'vscode',
                    clientVersion: this.clientVersion
                })
            });

            if (!response.ok) {
                console.error('[LicenseManager] Failed to start trial:', response.status);
                // Fall back to local trial check
                return this.checkLocalTrialFallback();
            }

            const data = await response.json() as AnonymousTrialResponse;

            // Sync to local backup
            if (data.startedAt) {
                const trialStart = new Date(data.startedAt).getTime();
                await this.syncTrialStart('anonymous', trialStart);
            }

            this.currentStatus = {
                isValid: data.status === 'trialing',
                status: data.status === 'trialing' ? 'trialing' :
                        data.status === 'expired' ? 'expired' : 'none',
                daysRemaining: data.daysRemaining,
                expiresAt: data.expiresAt,
            };

            this.updateStatusBar(this.currentStatus);
            return this.currentStatus;

        } catch (error) {
            console.error('[LicenseManager] Error starting trial:', error);
            return this.checkLocalTrialFallback();
        }
    }

    /**
     * Fallback to local trial check when offline.
     */
    private checkLocalTrialFallback(): LicenseStatus {
        const localData = this.readLocalBackup();
        const globalStateTimestamp = this.context.globalState.get<number>('trialStartTimestamp');

        const trialStart = localData?.trialStartTimestamp || globalStateTimestamp;

        if (!trialStart) {
            // No trial data - show as none (will prompt to try again when online)
            this.currentStatus = { isValid: false, status: 'none' };
            this.updateStatusBar(this.currentStatus);
            return this.currentStatus;
        }

        // Calculate trial expiry (30 days)
        const trialEnd = trialStart + (30 * 24 * 60 * 60 * 1000);
        const now = Date.now();
        const daysRemaining = Math.max(0, Math.ceil((trialEnd - now) / (24 * 60 * 60 * 1000)));

        this.currentStatus = {
            isValid: daysRemaining > 0,
            status: daysRemaining > 0 ? 'trialing' : 'expired',
            daysRemaining,
            expiresAt: new Date(trialEnd).toISOString(),
        };

        this.updateStatusBar(this.currentStatus);
        return this.currentStatus;
    }

    async login(): Promise<User | null> {
        console.log('[Karate Debug] Starting login...');
        const redirectUri = encodeURIComponent(`${API_BASE_URL}/auth/callback`);
        const authUrl = `https://github.com/login/oauth/authorize?client_id=${GITHUB_CLIENT_ID}&scope=user:email&redirect_uri=${redirectUri}`;

        console.log('[Karate Debug] Opening auth URL:', authUrl);
        vscode.env.openExternal(vscode.Uri.parse(authUrl));

        console.log('[Karate Debug] Waiting for auth code...');
        const code = await this.waitForAuthCode();
        console.log('[Karate Debug] Auth code received:', code ? 'yes' : 'no');
        if (!code) {
            vscode.window.showErrorMessage('Authentication cancelled or failed');
            return null;
        }

        try {
            // Pass machineId to link any anonymous trial to this user
            const response = await fetch(`${API_BASE_URL}/auth/github`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code, machineId: this.machineId })
            });

            if (!response.ok) {
                const error = await response.json() as { error?: string };
                throw new Error(error.error || 'Authentication failed');
            }

            const user = await response.json() as User;

            await this.context.globalState.update('userId', user.userId);
            await this.context.globalState.update('githubUsername', user.githubUsername);

            await this.activateMachine(user.userId);
            await this.validateLicense();

            vscode.window.showInformationMessage(`Logged in as ${user.githubUsername}`);
            return user;

        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Login failed: ${message}`);
            return null;
        }
    }

    private async waitForAuthCode(): Promise<string | null> {
        return new Promise((resolve) => {
            // Use the pre-registered URI handler
            this.pendingAuthResolve = resolve;

            // Timeout after 5 minutes
            setTimeout(() => {
                if (this.pendingAuthResolve === resolve) {
                    this.pendingAuthResolve = null;
                    resolve(null);
                }
            }, 5 * 60 * 1000);
        });
    }

    async logout(): Promise<void> {
        const userId = this.context.globalState.get<string>('userId');

        if (userId) {
            try {
                await fetch(`${API_BASE_URL}/license/deactivate`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ userId, machineId: this.machineId })
                });
            } catch {
                // Ignore errors during logout
            }
        }

        await this.context.globalState.update('userId', undefined);
        await this.context.globalState.update('githubUsername', undefined);
        // Note: Don't clear trialStartTimestamp - we want to preserve local trial tracking

        vscode.window.showInformationMessage('Logged out of Karate Debug');

        // Re-check anonymous trial status after logout
        // This ensures the status bar shows correct trial/expired state instead of 'none'
        await this.startOrCheckAnonymousTrial();
    }

    async validateLicense(): Promise<LicenseStatus> {
        const userId = this.context.globalState.get<string>('userId');

        if (!userId) {
            this.currentStatus = { isValid: false, status: 'none' };
            this.updateStatusBar(this.currentStatus);
            return this.currentStatus;
        }

        try {
            const response = await fetch(`${API_BASE_URL}/license/validate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId,
                    machineId: this.machineId,
                    machineName: os.hostname(),
                    platform: 'vscode',
                    clientVersion: this.clientVersion
                })
            });

            if (!response.ok) {
                // If user not found (404), clear local state and treat as new user
                if (response.status === 404) {
                    await this.context.globalState.update('userId', undefined);
                    await this.context.globalState.update('githubUsername', undefined);
                    await this.context.globalState.update('trialStartTimestamp', undefined);
                    this.currentStatus = { isValid: false, status: 'none' };
                    this.updateStatusBar(this.currentStatus);
                    return this.currentStatus;
                }
                throw new Error('Validation failed');
            }

            const data = await response.json() as {
                valid: boolean;
                status: string;
                daysRemaining?: number;
                expiresAt?: string;
            };

            // Get server's trial start and sync with local backup
            if (data.status === 'trialing' && data.expiresAt) {
                const serverTrialEnd = new Date(data.expiresAt).getTime();
                const serverTrialStart = serverTrialEnd - (30 * 24 * 60 * 60 * 1000); // Assuming 30-day trial
                const earliestStart = this.getEarliestTrialStart(serverTrialStart);

                if (earliestStart && earliestStart < serverTrialStart) {
                    // Local has earlier trial start - recalculate days remaining
                    const now = Date.now();
                    const trialEnd = earliestStart + (30 * 24 * 60 * 60 * 1000);
                    data.daysRemaining = Math.max(0, Math.ceil((trialEnd - now) / (24 * 60 * 60 * 1000)));
                    if (data.daysRemaining === 0) {
                        data.valid = false;
                        data.status = 'expired';
                    }
                }

                await this.syncTrialStart(userId, earliestStart || serverTrialStart);
            }

            this.currentStatus = {
                isValid: data.valid,
                status: data.status as LicenseStatus['status'],
                daysRemaining: data.daysRemaining,
                expiresAt: data.expiresAt,
                githubUsername: this.context.globalState.get('githubUsername')
            };

        } catch {
            // On error, check if user seems to have never had a valid session
            // If no local trial data exists, treat as new user, not expired
            const localData = this.readLocalBackup();
            const globalStateTimestamp = this.context.globalState.get<number>('trialStartTimestamp');

            if (!localData && !globalStateTimestamp) {
                // No prior trial data - treat as new user
                await this.context.globalState.update('userId', undefined);
                this.currentStatus = { isValid: false, status: 'none' };
            } else {
                this.currentStatus = { isValid: false, status: 'expired' };
            }
        }

        this.updateStatusBar(this.currentStatus);
        return this.currentStatus;
    }

    private async activateMachine(userId: string): Promise<boolean> {
        try {
            const response = await fetch(`${API_BASE_URL}/license/activate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId,
                    machineId: this.machineId,
                    machineName: os.hostname(),
                    platform: 'vscode',
                    clientVersion: this.clientVersion
                })
            });

            return response.ok;
        } catch {
            return false;
        }
    }

    async openSubscriptionPortal(): Promise<void> {
        const userId = this.context.globalState.get<string>('userId');

        if (!userId) {
            vscode.window.showErrorMessage('Please log in first');
            return;
        }

        try {
            const response = await fetch(`${API_BASE_URL}/subscription/portal`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId })
            });

            if (!response.ok) {
                throw new Error('Failed to open portal');
            }

            const data = await response.json() as { url: string };
            vscode.env.openExternal(vscode.Uri.parse(data.url));

        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Failed to open subscription portal: ${message}`);
        }
    }

    async startCheckout(): Promise<void> {
        let userId = this.context.globalState.get<string>('userId');

        // If not logged in, authenticate first (this will link the anonymous trial)
        if (!userId) {
            const user = await this.login();
            if (!user) {
                // Login was cancelled or failed
                return;
            }
            userId = user.userId;
        }

        try {
            const response = await fetch(`${API_BASE_URL}/subscription/checkout`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId })
            });

            if (!response.ok) {
                throw new Error('Failed to start checkout');
            }

            const data = await response.json() as { url: string };
            vscode.env.openExternal(vscode.Uri.parse(data.url));

        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            vscode.window.showErrorMessage(`Failed to start checkout: ${message}`);
        }
    }

    private formatTimeRemaining(expiresAt?: string, daysRemaining?: number): string {
        // Use daysRemaining from API if available (more accurate)
        if (daysRemaining !== undefined && daysRemaining >= 1) {
            return `${daysRemaining}d`;
        }

        if (!expiresAt) return '?';

        const now = Date.now();
        const expiryTime = new Date(expiresAt).getTime();
        const msRemaining = expiryTime - now;

        if (msRemaining <= 0) return '0m';

        const minutes = Math.ceil(msRemaining / (60 * 1000));
        const hours = Math.ceil(msRemaining / (60 * 60 * 1000));
        const days = Math.ceil(msRemaining / (24 * 60 * 60 * 1000));

        if (days >= 1) {
            return `${days}d`;
        } else if (hours >= 1) {
            return `${hours}h`;
        } else {
            return `${minutes}m`;
        }
    }

    private updateStatusBar(status: LicenseStatus): void {
        if (status.status === 'none') {
            // Offline on first install - show neutral status
            this.statusBarItem.text = '$(sync) Karate Debug';
            this.statusBarItem.tooltip = 'Checking license status...';
            this.statusBarItem.backgroundColor = undefined;
        } else if (status.status === 'active') {
            this.statusBarItem.text = '$(verified) Karate Debug Pro';
            this.statusBarItem.tooltip = `Licensed to ${status.githubUsername}`;
            this.statusBarItem.backgroundColor = undefined;
        } else if (status.status === 'trialing') {
            const timeLeft = this.formatTimeRemaining(status.expiresAt, status.daysRemaining);
            const isUrgent = status.daysRemaining !== undefined && status.daysRemaining <= 3;

            if (isUrgent) {
                this.statusBarItem.text = `$(warning) Trial: ${timeLeft} left`;
                this.statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
            } else {
                this.statusBarItem.text = `$(clock) Trial: ${timeLeft} left`;
                this.statusBarItem.backgroundColor = undefined;
            }
            this.statusBarItem.tooltip = 'Click for license info';
        } else {
            this.statusBarItem.text = '$(warning) Trial Expired';
            this.statusBarItem.tooltip = 'Click to purchase license';
            this.statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
        }
        this.statusBarItem.show();
    }

    getStatus(): LicenseStatus {
        return this.currentStatus;
    }

    isFeatureEnabled(_feature: 'debugging' | 'matchDiagnostics'): boolean {
        return this.currentStatus.isValid;
    }

    isTrialValid(): boolean {
        return this.currentStatus.isValid;
    }

    async showTrialExpiredMessage(): Promise<void> {
        const action = await vscode.window.showErrorMessage(
            'Your Karate Debug trial has expired. Please purchase a license or sign in if you already have one.',
            'Purchase License',
            'Sign In',
            'Contact Developer'
        );

        if (action === 'Purchase License') {
            await this.startCheckout();
        } else if (action === 'Sign In') {
            await this.login();
        } else if (action === 'Contact Developer') {
            vscode.env.openExternal(vscode.Uri.parse('https://www.karatedebug.com/?contact=general&ide=vscode'));
        }
    }

    async showLoginRequiredMessage(): Promise<void> {
        const action = await vscode.window.showInformationMessage(
            'Please sign in to start your free trial of Karate Debug.',
            'Sign In with GitHub'
        );

        if (action === 'Sign In with GitHub') {
            await this.login();
        }
    }

    getTrialInfo(): { startDate: Date | undefined; daysRemaining: number; isExpired: boolean; username?: string } {
        return {
            startDate: this.currentStatus.trialStartDate,
            daysRemaining: this.currentStatus.daysRemaining || 0,
            isExpired: !this.currentStatus.isValid,
            username: this.currentStatus.githubUsername
        };
    }
}
