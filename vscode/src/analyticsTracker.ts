import { API_BASE_URL } from './config';

export type SessionOutcome = 'completed' | 'stopped' | 'crashed' | 'timeout';
export type LifecycleEventType = 'initialized' | 'updated' | 'reinstalled' | 'uninstalled';

interface ActiveSession {
    sessionId: string;
    startTime: number;
}

/**
 * Analytics tracker for debug sessions and extension lifecycle events.
 * All tracking is fire-and-forget to avoid impacting normal operation.
 */
export class AnalyticsTracker {
    private machineId: string;
    private clientVersion: string;
    private platform: string = 'vscode';
    private activeSession: ActiveSession | null = null;

    constructor(machineId: string, clientVersion: string) {
        this.machineId = machineId;
        this.clientVersion = clientVersion;
    }

    /**
     * Start tracking a debug session.
     * Returns the session ID if successful.
     */
    async startSession(featureFile?: string): Promise<string | null> {
        try {
            const response = await fetch(`${API_BASE_URL}/session/start`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    machineId: this.machineId,
                    platform: this.platform,
                    clientVersion: this.clientVersion,
                    featureFile
                })
            });

            if (!response.ok) {
                console.error('[AnalyticsTracker] Failed to start session:', response.status);
                return null;
            }

            const data = await response.json() as { sessionId: string };
            this.activeSession = {
                sessionId: data.sessionId,
                startTime: Date.now()
            };
            return data.sessionId;
        } catch (error) {
            console.error('[AnalyticsTracker] Error starting session:', error);
            return null;
        }
    }

    /**
     * End the current debug session.
     */
    async endSession(outcome: SessionOutcome): Promise<void> {
        if (!this.activeSession) {
            return;
        }

        const { sessionId } = this.activeSession;
        this.activeSession = null;

        try {
            await fetch(`${API_BASE_URL}/session/end`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sessionId,
                    outcome
                })
            });
        } catch (error) {
            console.error('[AnalyticsTracker] Error ending session:', error);
        }
    }

    /**
     * Track a lifecycle event (initialized, updated, etc.)
     */
    async trackLifecycleEvent(
        eventType: LifecycleEventType,
        previousVersion?: string
    ): Promise<void> {
        try {
            await fetch(`${API_BASE_URL}/lifecycle/event`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    machineId: this.machineId,
                    platform: this.platform,
                    clientVersion: this.clientVersion,
                    eventType,
                    previousVersion
                })
            });
        } catch (error) {
            console.error('[AnalyticsTracker] Error tracking lifecycle event:', error);
        }
    }

    /**
     * Check if there is an active session.
     */
    hasActiveSession(): boolean {
        return this.activeSession !== null;
    }

    /**
     * Get the current session ID if active.
     */
    getActiveSessionId(): string | null {
        return this.activeSession?.sessionId ?? null;
    }
}

