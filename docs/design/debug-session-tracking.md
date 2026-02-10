# Debug Session Tracking - Design Document

## Overview

Track debug sessions for analytics to understand usage patterns, session duration, feature adoption, and version usage.

## Status

**Status:** Proposed  
**Created:** 2026-02-10  
**Author:** Ryan Stutzman

---

## Design Questions & Answers

### A) Client Version Updates

**Recommendation: Hybrid Approach**

| Table | Behavior | Purpose |
|-------|----------|---------|
| `anonymous_trials.client_version` | Update on every `/trial/start` call | Current version for this machine |
| `machine_activations.client_version` | Update on every `/license/validate` call | Current version for authenticated user |
| `debug_sessions.client_version` | Record at session start | Historical version per session |

### B) Trial Conversion Behavior

**Recommendation: No change needed**

Current flow is correct - when user signs in, they send current platform/client_version. The trial's original values are preserved in `anonymous_trials` for historical reference.

### C) Session Tracking Implementation

**Recommendation: Client-Side Tracking with Server-Generated Session IDs**

| Aspect | Recommendation |
|--------|----------------|
| Tracking Location | Client calls API on session start/end |
| Session ID | Server generates UUID, returns to client |
| Abnormal Termination | Background job marks sessions orphaned after 24 hours |

---

## Database Schema

```sql
CREATE TABLE debug_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Identification
    machine_id VARCHAR(255) NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    anonymous_trial_id UUID REFERENCES anonymous_trials(id) ON DELETE SET NULL,
    
    -- Timestamps
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    duration_seconds INTEGER,
    
    -- Client info
    platform VARCHAR(20) NOT NULL CHECK (platform IN ('vscode', 'intellij', 'unknown')),
    client_version TEXT NOT NULL,
    
    -- Session metadata
    feature_file_name TEXT,  -- Just filename, not full path (privacy)
    polyglot_enabled BOOLEAN DEFAULT FALSE,
    java_debugging_enabled BOOLEAN DEFAULT FALSE,
    js_debugging_enabled BOOLEAN DEFAULT FALSE,
    
    -- Outcome
    outcome VARCHAR(20) CHECK (outcome IN ('completed', 'stopped', 'crashed', 'orphaned')),
    
    -- Audit
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_debug_sessions_machine_id ON debug_sessions(machine_id);
CREATE INDEX idx_debug_sessions_user_id ON debug_sessions(user_id);
CREATE INDEX idx_debug_sessions_started_at ON debug_sessions(started_at DESC);
CREATE INDEX idx_debug_sessions_platform_started ON debug_sessions(platform, started_at DESC);
```

---

## API Endpoints

### New Endpoints

**POST /api/session/start**
```json
// Request
{ "machineId": "...", "platform": "vscode", "clientVersion": "0.2.0", 
  "userId": "optional", "featureFileName": "login.feature",
  "polyglotEnabled": true, "javaDebuggingEnabled": true, "jsDebuggingEnabled": false }

// Response
{ "sessionId": "uuid" }
```

**POST /api/session/end**
```json
// Request
{ "sessionId": "uuid", "outcome": "completed" }

// Response
{ "success": true, "durationSeconds": 342 }
```

### Modified Endpoints

- **POST /api/trial/start** - Update `client_version` on existing trials
- **POST /api/license/validate** - Update `client_version` on machine activations

---

## Files to Modify

### karate-debug-api
- `supabase/migrations/011_debug_sessions.sql` - NEW
- `lib/supabase.ts` - Add DebugSession interface
- `lib/validation.ts` - Add session schemas
- `api/session/start.ts` - NEW
- `api/session/end.ts` - NEW
- `api/trial/start.ts` - Update client_version
- `api/license/validate.ts` - Update client_version

### karate-debug (VS Code)
- `vscode/src/debugAdapter.ts` - Add session tracking
- `vscode/src/sessionTracker.ts` - NEW utility

### karate-debug (IntelliJ)
- `LicenseApiClient.java` - Add startSession/endSession
- `SessionTracker.java` - NEW
- `KarateDebugProcess.java` - Call session tracker

---

## Analytics Views

| View | Purpose |
|------|---------|
| `analytics_daily_active_users` | DAU/WAU/MAU by platform |
| `analytics_version_adoption` | Version usage distribution |
| `analytics_session_duration` | Session length buckets |
| `analytics_feature_usage` | Polyglot/Java/JS adoption |
| `analytics_session_outcomes` | Completed vs stopped vs crashed |

---

## Implementation Order

1. **Phase 1:** Database migration + API endpoints
2. **Phase 2:** VS Code integration
3. **Phase 3:** IntelliJ integration  
4. **Phase 4:** Analytics views

---

## Considerations

- **Privacy:** No code content transmitted, only filenames (not paths)
- **Offline:** Skip tracking silently, debugging still works
- **Rate Limiting:** 60 req/min per machineId
- **Orphan Cleanup:** Mark sessions orphaned after 24 hours without end

