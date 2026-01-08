# Karate Debug IntelliJ Plugin Project

## Executive Summary

This document outlines the comprehensive plan to develop an IntelliJ IDEA plugin version of the existing Karate Debug VS Code extension. The project aims to bring the same powerful debugging capabilities to IntelliJ users while maintaining feature parity and leveraging platform-specific strengths.

**Project Goals:**
- Develop a fully-featured IntelliJ plugin with debugging capabilities for Karate `.feature` files
- Maintain 90%+ feature parity with the VS Code extension
- Implement cross-platform licensing and user management
- Establish sustainable dual-platform development and release processes

## Current State Analysis

### Existing Codebase Architecture

**VS Code Extension (`karate-debug`)**
```
karate-debug/
├── src/                           # TypeScript extension code
│   ├── extension.ts               # Main entry point, CodeLens, Feature Explorer
│   ├── debugAdapter.ts            # DAP client, spawns Java debug server
│   ├── licensing.ts               # License management, trial flow
│   └── config.ts                  # Build-time configuration
├── debug-server/                  # Java DAP server
│   ├── src/main/java/com/j8d/karate/debug/
│   │   ├── DebugServer.java       # Main DAP server
│   │   ├── DapSession.java        # Debug session management
│   │   └── KarateDebugger.java    # Karate execution engine
│   └── pom.xml
├── resources/                     # Extension assets, JAR files
├── syntaxes/                      # Karate syntax highlighting
└── package.json                   # Extension manifest
```

**Backend API (`karate-debug-api`)**
- Node.js/Express API hosted on Vercel
- Endpoints: trial management, GitHub OAuth, Stripe subscriptions
- Database: PostgreSQL with tables for trials, users, subscriptions

**Marketing Site (`karate-debug-marketing`)**
- Static website with documentation, demos, pricing
- Hosted on Vercel with custom domain

### Feature Inventory

| Feature | VS Code Implementation | Complexity | IntelliJ Equivalent |
|---------|----------------------|------------|-------------------|
| Breakpoint debugging | DAP + TypeScript | High | XDebugger + Java |
| CodeLens buttons | VS Code API | Medium | Gutter icons |
| Feature Explorer | Tree view provider | Medium | Tool window |
| Environment switching | Status bar item | Low | Status bar widget |
| Variable inspection | DAP variables view | High | XDebugger variables |
| Hot-swap variables | DAP setVariable | High | XDebugger setValue |
| Match diagnostics | Diagnostics API | Medium | Inspections |
| Syntax highlighting | TextMate grammar | Low | Lexer/Parser |
| Project detection | Workspace folders | Low | Project model |
| Licensing/trials | HTTP client | Low | HTTP client |

## Technical Architecture

### Proposed Repository Structure

```
karate-debug/
├── shared/                        # Shared components
│   ├── karate-debug-core/         # Core debugging logic
│   │   ├── src/main/java/com/j8d/karate/debug/
│   │   │   ├── core/              # Debug engine, Karate execution
│   │   │   ├── api/               # API client for licensing
│   │   │   ├── licensing/         # License management logic
│   │   │   └── util/              # Machine ID, environment utils
│   │   └── pom.xml
│   └── debug-server/              # DAP server (moved from root)
│       ├── src/main/java/com/j8d/karate/debug/dap/
│       └── pom.xml
├── vscode/                        # VS Code extension
│   ├── src/                       # TypeScript source
│   ├── package.json
│   └── tsconfig.json
├── intellij/                      # IntelliJ plugin
│   ├── src/main/java/com/j8d/karate/intellij/
│   │   ├── KaratePlugin.java      # Main plugin class
│   │   ├── debug/                 # Debug integration
│   │   ├── ui/                    # Tool windows, actions
│   │   ├── lang/                  # Language support
│   │   └── project/               # Project detection
│   ├── src/main/resources/
│   │   ├── META-INF/plugin.xml    # Plugin descriptor
│   │   └── icons/                 # UI icons
│   ├── build.gradle.kts
│   └── gradle.properties
├── api/                           # Backend API (unchanged)
├── marketing/                     # Website (unchanged)
├── .github/workflows/             # Multi-platform CI/CD
└── docs/                          # Shared documentation
```

### Shared Components Strategy

**✅ Fully Reusable (90% of backend logic)**
- `KarateDebugEngine` - Core Karate execution and debugging
- `LicenseManager` - Trial and subscription management
- `ApiClient` - Communication with karate-debug-api
- `EnvironmentManager` - Karate environment switching
- `MachineIdGenerator` - Cross-platform machine identification
- `MatchExpressionValidator` - Karate match expression parsing

**🔄 Adaptable (core logic reusable, interface changes)**
- `ProjectDetector` - Maven/Gradle project detection
- `FeatureFileWatcher` - File system monitoring
- `ConfigurationManager` - Settings persistence

**❌ Platform-Specific (requires complete rewrite)**
- UI components (CodeLens vs Gutter icons)
- IDE integration APIs
- Debug adapter registration
- Extension/plugin lifecycle

### Debug Integration Architecture

**Challenge:** VS Code uses Debug Adapter Protocol (DAP) with external processes, while IntelliJ uses XDebugger framework with in-process debugging.

**Solution:** Hybrid approach maintaining DAP server for consistency:

```java
// IntelliJ XDebugger bridge to DAP server
public class KarateDebugProcess extends XDebugProcess {
    private final DapClient dapClient;
    private final ExecutorService executor;
    
    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        executor.submit(() -> dapClient.sendStepOver());
    }
    
    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        executor.submit(() -> dapClient.sendStepInto());
    }
    
    // Bridge DAP events to IntelliJ XDebugger
    private void handleDapEvent(DapEvent event) {
        ApplicationManager.getApplication().invokeLater(() -> {
            switch (event.getType()) {
                case STOPPED:
                    getSession().positionReached(createSuspendContext(event));
                    break;
                case TERMINATED:
                    getSession().stop();
                    break;
            }
        });
    }
}
```

## Implementation Plan

### Phase 1: Foundation

**1.1: Repository Restructuring**
- [ ] Create monorepo structure
- [ ] Extract shared logic into `karate-debug-core` module
- [ ] Move `debug-server` to `shared/debug-server`
- [ ] Update VS Code extension to use shared modules
- [ ] Verify VS Code extension still works

**1.2: IntelliJ Plugin Skeleton**
- [ ] Set up IntelliJ plugin project structure
- [ ] Create basic `plugin.xml` with file type registration
- [ ] Implement Karate file type and icon
- [ ] Basic syntax highlighting using Lexer/Parser
- [ ] Plugin loads and recognizes `.feature` files

**1.3: Project Integration**
- [ ] Implement `KarateProjectDetector` for Maven/Gradle projects
- [ ] Add project-level settings and configuration
- [ ] Create basic tool window for Feature Explorer
- [ ] Integrate shared `karate-debug-core` dependency

**Deliverables:**
- Working IntelliJ plugin that loads and recognizes Karate projects
- Restructured codebase with shared components
- Updated VS Code extension using shared modules

### Phase 2: Core Debugging Features

**2.1: Debug Infrastructure**
- [ ] Implement `KarateDebugRunner` and run configurations
- [ ] Create DAP client bridge to XDebugger
- [ ] Basic breakpoint support in `.feature` files
- [ ] Debug session lifecycle management
- [ ] Process spawning and communication

**2.2: Debug UI Integration**
- [ ] Variable inspection in IntelliJ debugger
- [ ] Call stack representation
- [ ] Step over/into/out functionality
- [ ] Breakpoint management and validation
- [ ] Debug console integration

**2.3: Advanced Debug Features**
- [ ] Hot-swap variable modification
- [ ] Conditional breakpoints
- [ ] Expression evaluation
- [ ] Thread management for Karate scenarios
- [ ] Error handling and recovery

**Deliverables:**
- Fully functional debugging with breakpoints and variable inspection
- Feature parity with VS Code debugging capabilities
- Comprehensive test suite for debug functionality

### Phase 3: UI and User Experience

**3.1: Gutter Icons and Actions**
- [ ] Implement `LineMarkerProvider` for run/debug gutter icons
- [ ] Context menu actions for scenarios and features
- [ ] Keyboard shortcuts and action mappings
- [ ] Integration with IntelliJ's run/debug infrastructure

**3.2: Feature Explorer Tool Window**
- [ ] Tree view of all Karate features and scenarios
- [ ] Search and filtering capabilities
- [ ] Run/debug actions from tree view
- [ ] Refresh and auto-update functionality
- [ ] Integration with project structure changes

**3.3: Environment and Configuration**
- [ ] Status bar widget for environment switching
- [ ] Settings UI using IntelliJ's `Configurable` interface
- [ ] Environment persistence and project-level settings
- [ ] Integration with IntelliJ's settings system

**Deliverables:**
- Complete UI feature parity with VS Code extension
- Intuitive IntelliJ-native user experience
- Comprehensive settings and configuration options

### Phase 4: Advanced Features and Polish

**4.1: Match Expression Diagnostics**
- [ ] Implement `LocalInspectionTool` for match validation
- [ ] Real-time error highlighting and quick fixes
- [ ] Integration with IntelliJ's inspection framework
- [ ] Performance optimization for large files

**4.2: Licensing Integration**
- [ ] Cross-platform license management
- [ ] Trial flow and GitHub OAuth integration
- [ ] Status bar license indicator
- [ ] Purchase flow and subscription management

**4.3: Testing and Bug Fixes**
- [ ] Comprehensive testing across IntelliJ versions
- [ ] Performance testing and optimization
- [ ] Bug fixes and stability improvements
- [ ] Documentation and help integration

**Deliverables:**
- Production-ready IntelliJ plugin
- Cross-platform licensing system
- Complete documentation and user guides

## Build and Deployment Strategy

### Multi-Platform Build System

**GitHub Actions Workflow:**
```yaml
name: Build All Platforms
on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  build-shared:
    # Build karate-debug-core and debug-server JARs
    
  build-vscode:
    needs: build-shared
    # Build .vsix using existing process
    
  build-intellij:
    needs: build-shared
    # Build .zip using Gradle IntelliJ plugin
    
  test-all:
    # Run tests for both platforms
    
  release:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: [build-vscode, build-intellij]
    # Publish to both VS Code and JetBrains marketplaces
```

**Version Synchronization:**
- Single version number in root `version.txt`
- Both platforms read from same source
- Automated version bumping in CI/CD

**Marketplace Publishing:**
- **VS Code**: Existing `vsce` process
- **IntelliJ**: Gradle `publishPlugin` task to JetBrains Marketplace
- Automated publishing on tag push

### IntelliJ Build Configuration

```kotlin
// intellij/build.gradle.kts
plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.2"
}

intellij {
    version.set("2023.2")
    type.set("IC") // Community Edition
    plugins.set(listOf("java", "maven", "gradle"))
}

dependencies {
    implementation(project(":shared:karate-debug-core"))
    implementation(files("libs/karate-debug-server-1.0.0.jar"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("232") // IntelliJ 2023.2
        untilBuild.set("241.*") // IntelliJ 2024.1
    }
    
    publishPlugin {
        token.set(System.getenv("JETBRAINS_TOKEN"))
    }
}
```

## Cross-Platform Licensing Strategy

### Machine Identification

**Challenge:** Ensure same user can use trial/license across both IDEs

**Solution:** Platform-aware machine ID generation:
```java
public class MachineIdGenerator {
    public static String generateMachineId() {
        String hostname = getHostname();
        String username = System.getProperty("user.name");
        String platform = detectPlatform(); // "vscode" or "intellij"
        
        // Same base ID for both platforms
        String baseId = sha256(hostname + ":" + username);
        return baseId + "-" + platform;
    }
}
```

### API Updates

**Trial Management:**
- Track platform in `anonymous_trials` table
- Allow trial transfer between platforms
- Unified trial status endpoint

**Subscription Management:**
- Single subscription covers both platforms
- Platform usage tracking for analytics
- Cross-platform license validation

### User Experience

**Trial Flow:**
1. User starts trial in either IDE (30 days)
2. Trial automatically available in other IDE
3. GitHub sign-in links trials across platforms
4. Single purchase covers both IDEs

**Status Indicators:**
- VS Code: Status bar item
- IntelliJ: Status bar widget
- Consistent messaging and actions

## Risk Assessment and Mitigation

### High Risk Items

**1. IntelliJ Platform Complexity**
- **Risk:** Steeper learning curve than VS Code API
- **Impact:** Development delays, feature gaps
- **Mitigation:**
    - Early prototyping and proof-of-concepts

**2. DAP to XDebugger Integration**
- **Risk:** Impedance mismatch between debugging models
- **Impact:** Poor debugging experience, performance issues
- **Mitigation:**
    - Prototype early with simple scenarios
    - Consider native XDebugger implementation as fallback
    - Performance testing throughout development

**3. Feature Parity Challenges**
- **Risk:** Some VS Code features may not translate directly
- **Impact:** User disappointment, adoption issues
- **Mitigation:**
    - Document feature differences upfront
    - Leverage IntelliJ-specific strengths
    - User feedback during beta testing

### Medium Risk Items

**4. Build System Complexity**
- **Risk:** Managing dependencies across multiple platforms
- **Impact:** Build failures, deployment issues
- **Mitigation:**
    - Incremental migration approach
    - Shared CI/CD templates
    - Automated testing of build artifacts

**5. Cross-Platform Licensing**
- **Risk:** License validation failures across platforms
- **Impact:** User frustration, revenue loss
- **Mitigation:**
    - Comprehensive testing of license flows
    - Fallback mechanisms for API failures
    - Clear error messaging and support

### Low Risk Items

**6. User Adoption**
- **Risk:** Existing users prefer current IDE
- **Impact:** Lower than expected adoption
- **Mitigation:**
    - Cross-platform licensing incentives
    - Gradual rollout with beta program
    - IntelliJ-specific feature highlights

## Success Metrics and KPIs

### Technical Metrics
- **Feature Parity:** 90%+ of VS Code features available in IntelliJ
- **Performance:** Debug session startup <5 seconds
- **Stability:** <2% crash rate in production
- **Compatibility:** Support IntelliJ 2023.2+ (95% of user base)

### Business Metrics
- **Adoption:** 25% of existing users try IntelliJ version within 6 months
- **Retention:** 60% of trial users in IntelliJ convert to paid
- **Cross-Platform:** 15% of users active on both platforms
- **Revenue:** 40% increase in total revenue within 12 months

### Quality Metrics
- **Bug Reports:** <5% related to platform differences
- **User Satisfaction:** 4.5+ stars on JetBrains Marketplace
- **Support Load:** <10% increase in support tickets
- **Documentation:** 95% of features documented with examples

## Resource Requirements

### Development Team

**Senior Java Developer (IntelliJ Specialist)**
- **Duration:** 6 months full-time
- **Responsibilities:** Core plugin development, debug integration, UI components
- **Skills:** IntelliJ Platform SDK, XDebugger, Swing/UI development

**Frontend Developer (VS Code Maintenance)**
- **Duration:** 2 months part-time
- **Responsibilities:** Maintain VS Code extension, shared component integration
- **Skills:** TypeScript, VS Code API, DAP

**DevOps Engineer**
- **Duration:** 1 month full-time
- **Responsibilities:** CI/CD setup, build automation, deployment pipelines
- **Skills:** GitHub Actions, Gradle, Maven, marketplace publishing

### Infrastructure Requirements

**Development Environment:**
- JetBrains Marketplace publisher account

**Testing Infrastructure:**
- Multiple IntelliJ versions (2023.2, 2023.3, 2024.1)
- Automated UI/API testing framework (built-in using Karate?)

**Deployment:**
- JetBrains Marketplace publishing
- Monitoring and analytics for both platforms


## Marketing and Go-to-Market Strategy

### Target Audience

**Primary:** Existing Karate users who prefer IntelliJ IDEA
- Java developers using IntelliJ for primary development
- Teams with mixed IDE preferences
- Enterprise users with IntelliJ licenses

**Secondary:** New Karate users discovering through IntelliJ
- API testing teams evaluating tools
- Java developers new to Karate framework
- IntelliJ plugin marketplace browsers

### Launch Strategy

**Phase 1: Beta Release**
- Private beta with existing VS Code users
- Feedback collection and iteration
- Documentation and tutorial creation

**Phase 2: Public Release**
- JetBrains Marketplace submission

### Marketing Materials Updates

**Website Changes:**
- Platform comparison page
- IntelliJ-specific screenshots and demos
- Updated pricing page with cross-platform benefits

**Documentation:**
- IntelliJ installation and setup guide
- Feature comparison matrix
- Migration guide for VS Code users

**Content Marketing:**
- "Debugging Karate in IntelliJ" blog series
- Video tutorials and demos
- Conference talk submissions

## Timeline and Milestones

**Key Milestone:** Working IntelliJ plugin that recognizes Karate projects
**Key Milestone:** Feature-complete debugging capabilities
**Key Milestone:** Public release on JetBrains Marketplace
**Key Milestone:** Established user base and revenue growth

## Conclusion

The IntelliJ plugin project represents a significant opportunity to expand the Karate Debug user base and revenue while providing a superior debugging experience for IntelliJ users. The technical approach of shared components with platform-specific UI layers minimizes development risk while ensuring feature parity.

Key success factors:
1. **Technical Excellence:** Leveraging shared components while embracing platform strengths
2. **User Experience:** Maintaining familiar workflows while adding IntelliJ-native features
3. **Cross-Platform Strategy:** Unified licensing and seamless user experience across IDEs
4. **Execution:** Phased approach with clear milestones and risk mitigation


**Next Steps:**
1. Begin Phase 1 repository restructuring
2. Set up development and testing infrastructure
3. Initiate JetBrains Marketplace publisher account
4. Create detailed technical specifications for each component

This project positions Karate Debug as the premier debugging solution for Karate tests across all major IDEs, significantly expanding market reach and establishing a sustainable competitive advantage.

---

## Implementation Progress Log

### Phase 1.1: Repository Restructuring

**Status:** IN PROGRESS
**Branch:** `ij-plugin`
**Started:** 2026-01-07

#### Completed Tasks

- [x] **Create monorepo directory structure** - Created `shared/`, `vscode/`, and `intellij/` directories
- [x] **Move VS Code extension to vscode/** - Moved `src/`, `package.json`, `tsconfig.json`, `syntaxes/`, `resources/`, `scripts/`, `out/`, `language-configuration.json`, `eslint.config.mjs`, `node_modules/`
- [x] **Move debug-server to shared/** - Moved `debug-server/` to `shared/debug-server/`
- [x] **Update VS Code extension paths** - Updated `debugAdapter.ts` to look for JAR in new monorepo location (`shared/debug-server/target/`)
- [x] **Verify VS Code extension builds** - Successfully ran `npm run compile` and `npm run package`
- [x] **Verify debug-server builds** - Successfully ran `mvn clean package` in `shared/debug-server/`

#### Current Repository Structure

```
karate-debug/
├── shared/
│   └── debug-server/          # Java DAP server (moved from root)
├── vscode/                    # VS Code extension (moved from root)
│   ├── src/
│   ├── package.json
│   ├── resources/
│   └── ...
├── intellij/                  # IntelliJ plugin (empty, to be created)
├── test-fixtures/             # Sample Karate project for testing
├── ijproject.md               # This project plan
├── LICENSE
├── CHANGELOG.md
└── README.md
```

### Phase 1.2: IntelliJ Plugin Skeleton

**Status:** COMPLETE
**Started:** 2026-01-07
**Completed:** 2026-01-07

#### Completed Tasks

- [x] **Set up Gradle project structure** - Created `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` with IntelliJ Platform Gradle Plugin 2.2.1
- [x] **Create plugin.xml** - Defined plugin metadata, dependencies, file type registration, run configuration, debug support
- [x] **Implement custom Karate language support** - Created full language infrastructure without Gherkin plugin dependency:
  - `KarateLanguage` - Language definition
  - `KarateFileType` - File type for `.feature` files
  - `KarateLexer` - Tokenizer for Karate syntax (Feature, Scenario, Given/When/Then, tags, comments)
  - `KarateParser` / `KarateParserDefinition` - PSI structure
  - `KarateSyntaxHighlighter` / `KarateSyntaxHighlighterFactory` - Syntax highlighting
  - `KarateFile` / `KaratePsiElement` - PSI file and element classes
- [x] **Create run configuration infrastructure** - `KarateConfigurationType`, `KarateConfigurationFactory`, `KarateRunConfiguration`, `KarateRunProfileState`
- [x] **Create debug infrastructure stubs** - `KarateDebugProgramRunner`, `KarateDebugProcess`, `KarateBreakpointType`
- [x] **Create gutter icon provider** - `KarateRunLineMarkerContributor` using token-based detection
- [x] **Create project detection** - `KarateProjectService`, `KarateProjectStartupActivity`
- [x] **Create UI stubs** - `KarateToolWindowFactory`, action classes
- [x] **Verify plugin builds** - Successfully ran `./gradlew build`

### Phase 1.5: Debug Infrastructure

**Status:** COMPLETE
**Started:** 2026-01-07
**Completed:** 2026-01-07

#### Completed Tasks

- [x] **Implement KarateDapClient** - Full DAP protocol implementation:
  - Socket-based communication with Content-Length framing
  - Request/response handling with CompletableFuture
  - Event handling (stopped, terminated, output)
  - Initialize, launch, configurationDone sequence
  - Stepping commands (stepOver, stepIn, stepOut, continue)
  - Breakpoint management per file
  - Stack trace, scopes, and variables retrieval

- [x] **Implement KarateDebugProcess** - XDebugger bridge:
  - Spawns debug-server JAR as subprocess
  - Handles session lifecycle (start, stop)
  - `onStopped()` callback for breakpoint hits
  - Console logging integration
  - KarateSuspendContext for position tracking

- [x] **Create KarateExecutionStack** - Stack frame management:
  - Parses DAP stackTrace responses
  - Creates KarateStackFrame instances

- [x] **Create KarateStackFrame** - Frame representation:
  - Maps DAP frames to XSourcePosition
  - Customized presentation with file:line
  - Fetches scopes and displays variable groups

- [x] **Create KarateVariableGroup** - Variable display:
  - Groups variables by scope (Local, Global)
  - KarateVariable for individual values
  - Recursive expansion for objects/arrays
  - Type-aware icons

- [x] **Complete breakpoint support** - Already implemented:
  - `KarateBreakpointType` for .feature files
  - `KarateBreakpointHandler` wires to DAP client
  - `KarateBreakpointProperties` for state

#### Architecture

```
IntelliJ XDebugger <-> KarateDebugProcess <-> KarateDapClient <-> Socket <-> debug-server JAR
                            |
                            v
                    KarateSuspendContext
                            |
                    KarateExecutionStack
                            |
                    KarateStackFrame[]
                            |
                    KarateVariableGroup[]
                            |
                    KarateVariable[]
```

#### Key Decision: Custom Language vs Gherkin Plugin

Originally planned to depend on the Gherkin plugin for `.feature` file support. Discovered that:
- Gherkin plugin is **not bundled** with IntelliJ (requires separate installation)
- This would add friction for users

**Decision:** Implemented custom Karate language support with our own lexer/parser/syntax highlighter. This:
- Removes external plugin dependency
- Gives us full control over Karate-specific syntax (embedded JS, JSON, etc.)
- Uses simple token-based parsing for scenario detection (sufficient for run/debug features)

#### Current Plugin Structure

```
intellij/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── java/com/j8d/karate/intellij/
    │   ├── lang/                    # Custom Karate language
    │   │   ├── KarateLanguage.java
    │   │   ├── KarateFileType.java
    │   │   ├── KarateLexer.java
    │   │   ├── KarateParser.java
    │   │   ├── KarateParserDefinition.java
    │   │   ├── KarateSyntaxHighlighter.java
    │   │   └── ...
    │   ├── run/                     # Run configurations
    │   │   ├── KarateConfigurationType.java
    │   │   ├── KarateRunConfiguration.java
    │   │   ├── KarateRunLineMarkerContributor.java
    │   │   └── ...
    │   ├── debug/                   # Debug infrastructure
    │   │   ├── KarateDebugProgramRunner.java
    │   │   ├── KarateDebugProcess.java
    │   │   └── ...
    │   ├── project/                 # Project detection
    │   ├── ui/                      # Tool windows
    │   └── actions/                 # Menu actions
    └── resources/
        ├── META-INF/plugin.xml
        └── icons/

```

#### Next Steps

- [ ] Phase 2.1: Test end-to-end debugging in IntelliJ
- [ ] Phase 2.2: Implement variable modification (hot-swap)
- [ ] Phase 2.3: Add conditional breakpoint support
- [ ] Phase 3.1: Implement Feature Explorer tool window UI

---

## Technical Debt

### Build System Unification

**Task:** Migrate `shared/debug-server` from Maven to Gradle

**Current State:**
- `shared/debug-server/` uses Maven (`pom.xml`)
- `intellij/` uses Gradle (`build.gradle.kts`)

**Rationale:**
- Consistency across the project - single build system
- Easier dependency management between shared modules
- Simplified CI/CD pipeline
- Can use Gradle composite builds for local development

**Effort:** Low-Medium

**Priority:** Low (not blocking, but nice to have)

**Approach:**
1. Create `build.gradle.kts` for debug-server
2. Migrate dependencies from `pom.xml`
3. Update IntelliJ plugin to consume debug-server as Gradle subproject
4. Update VS Code build scripts to use Gradle instead of Maven
5. Remove `pom.xml` after verification

---

## Progress Log

### 2026-01-07: Phase 1 Complete

**Milestone:** Basic debugging functionality is now working in IntelliJ!

**Completed:**
- Repository restructured to monorepo (shared/, vscode/, intellij/)
- IntelliJ plugin skeleton with Gradle build
- Custom Karate language support (lexer, parser, syntax highlighting)
- Project detection for Maven/Gradle with Karate dependencies
- Feature Explorer tool window with scenario tree view
- Run/Debug gutter icons on Feature/Scenario lines
- XDebugger integration with DAP protocol client
- Breakpoint support - breakpoints hit and debugger pauses
- Variable inspection in debug view
- Stack frames display

**Key Fixes Applied:**
1. Fixed `ReadAction` requirement for file index operations in `KarateProjectService`
2. Added `KarateEditorsProvider` for XVariablesView (was returning null)
3. Fixed classpath generation removing `!/` suffixes from JAR paths
4. Added breakpoint queuing for breakpoints set before DAP connection
5. Added null checks in `KarateBreakpointHandler`
6. Implemented `createDocument()` in `KarateEditorsProvider` (was causing AbstractMethodError)
7. Fixed Maven property resolution for version detection (e.g., `${karate.version}` -> `1.5.2`)
8. Fixed EDT slow operation warnings:
   - Moved project detection to background thread using `ProgressManager`
   - Made `isKarateProject()` return cached values only (no detection on EDT)
   - Updated tool window to populate tree in background thread
   - Made `KarateToolWindowFactory` implement `DumbAware` and always be available
9. Updated Gradle regex to support `io.karatelabs` group ID (newer Karate releases)
10. Fixed scenario-level debugging (was running entire feature):
    - Updated `KarateDapClient.sendLaunch()` to append line number to feature path (e.g., `/path/feature.feature:23`)
    - Updated `KarateRunConfigurationProducer` to detect scenario line number (not just name)
    - Implemented sidebar run/debug actions in `KarateToolWindowContent.runSelected()`

**Test Results:**
- Debug server starts correctly
- DAP connection established
- Breakpoints set and hit
- Tests execute and pass (2 scenarios, 0 failed)
- Debugger properly pauses at breakpoints
- No more EDT slow operation warnings
- Tool window displays correctly with feature files
- Karate version detected correctly (1.5.2)
- Scenario-level debugging works from both gutter icons and sidebar
11. Added settings UI and status bar widgets:
    - Created `KarateSettingsConfigurable` for Preferences > Tools > Karate Debug
    - Added all VS Code extension settings: environments, logLevel, matchDiagnostics options
    - Created `KarateEnvironmentWidget` status bar widget to switch environments
    - Created `KarateLogLevelWidget` status bar widget to switch log levels
    - Wired log level and environment from settings to debug server launch args

**Next Steps (Phase 2):**
- Variable modification (hot reload)
- Expression evaluation
- Step into/over/out improvements
- Run without debugging
- License integration