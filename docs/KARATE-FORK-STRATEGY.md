# Karate Fork Strategy

## Overview

This document outlines the strategy for maintaining a custom fork of Karate (`j8d-org/karate`) to support advanced debugging features in the Karate Debug extension.

## Why Fork Karate?

1. **Debugging instrumentation** - Add logging and hooks that help diagnose issues
2. **Custom RuntimeHook enhancements** - Expose internal state not available in public API
3. **Immediate bug fixes** - Fix issues without waiting for upstream releases
4. **Debugging-specific features** - Add capabilities that wouldn't belong in upstream

## Repository Structure

- **Upstream**: `karatelabs/karate` (official Karate repository)
- **Fork**: `j8d-org/karate` (our instrumented fork)
- **Distribution**: JitPack (`com.github.j8d-org.karate:karate-core`)

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `master` | Synced with upstream `karatelabs/karate` |
| `debug-main` | Our stable instrumented version for releases |
| `debug-*` | Feature branches for specific investigations |

## Switching Between Official and Fork

### For Development/Debugging (use fork)

```xml
<properties>
    <karate.version>debug-main-SNAPSHOT</karate.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.github.j8d-org.karate</groupId>
        <artifactId>karate-core</artifactId>
        <version>${karate.version}</version>
    </dependency>
</dependencies>

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### For Production Releases (use official)

```xml
<properties>
    <karate.version>1.5.2</karate.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.karatelabs</groupId>
        <artifactId>karate-core</artifactId>
        <version>${karate.version}</version>
    </dependency>
</dependencies>
```

## Syncing with Upstream

```bash
cd /path/to/karate-fork

# Add upstream remote (one-time)
git remote add upstream https://github.com/karatelabs/karate.git

# Fetch upstream changes
git fetch upstream

# Merge upstream into master
git checkout master
git merge upstream/master
git push origin master

# Rebase debug-main onto updated master
git checkout debug-main
git rebase master
git push origin debug-main --force-with-lease
```

## Tasks

### Phase 1: Initial Setup (Current)
- [x] Fork `karatelabs/karate` to `j8d-org`
- [x] Create `debug-scenario-runtime` branch with diagnostic logging
- [x] Configure JitPack in debug-server pom.xml
- [ ] Verify instrumented fork works in CI

### Phase 2: Permanent Structure
- [ ] Create `debug-main` branch as stable instrumented version
- [ ] Add Maven profile to toggle between official/fork
- [ ] Document all modifications made to Karate source
- [ ] Set up upstream sync workflow (manual or automated)

### Phase 3: Production Release Process
- [ ] Create release checklist that includes switching to official Karate
- [ ] Add CI job to verify builds work with official Karate
- [ ] Consider automated nightly builds against upstream

## Modifications Log

Track all changes made to the fork here:

| File | Change | Purpose | Date |
|------|--------|---------|------|
| `ScenarioRuntime.java` | Added `[KARATE-DEBUG]` logging | Diagnose beforeScenario not being called | 2026-02-04 |

## Contributing Upstream

If a fix would benefit the Karate community:
1. Create a clean branch from upstream master
2. Make minimal, focused changes
3. Submit PR to `karatelabs/karate`
4. Once merged, sync our fork

## Notes

- JitPack builds take ~5-10 minutes on first request (cached after)
- Use commit hashes for reproducible builds: `com.github.j8d-org.karate:karate-core:abc1234`
- Local development can use `mvn install` on the fork for faster iteration

