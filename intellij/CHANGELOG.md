# Changelog

All notable changes to the Karate Debug IntelliJ plugin will be documented in this file.

## [0.2.5] - 2026-05-05

### Fixed
- Fixed plugin publishing issue that caused "plugin archive file cannot be extracted" error on JetBrains Marketplace

## [0.2.4] - 2026-04-29

### Changed
- Open-source release: Removed all licensing, trial, and payment functionality

## [0.2.3] - 2026-03-09

### Fixed
- Replaced deprecated `ProcessAdapter` with `ProcessListener` interface
- Replaced deprecated `ActionUtil.performActionDumbAwareWithCallbacks()` with modern `ActionUtil.invokeAction()` (also eliminates the scheduled-for-removal `AnActionEvent.createFromAnAction()` API that was previously used)
- All deprecated API usages eliminated to ensure compatibility with future IntelliJ Platform releases

## [0.2.2] - 2026-03-09

### Fixed
- Fixed critical variable scoping bug where called scenarios (with @ignore tag) could not access parent scope variables or magic variables (karate, response, etc.) - variables were stored in GraalVM JS bindings but not exposed in the Variables view or evaluation context
- Improved variable evaluation in debug console to correctly handle magic variables from GraalVM JS bindings
- Improved socket error suppression to only filter shutdown-related errors (containing "Socket closed" or "Broken pipe") while preserving legitimate network errors during active debug sessions

## [0.2.1] - 2026-02-27

### Fixed
- Force HTTP/1.1 for analytics API calls to ensure compatibility with all server configurations

## [0.2.0] - 2026-02-10

### Added
- Step filtering settings - control which code is skipped when stepping through Java code:
  - Skip Karate Framework Classes (com.intuit.karate.*) - enabled by default
  - Skip Karate Dependencies (jsonpath, netty, slf4j, etc.) - enabled by default
  - Settings available in Preferences > Tools > Karate Debug
- Missing sources notification - when stepping into library code without sources:
  - Shows notification with "Download Sources" button
  - Runs `mvn dependency:sources` for Maven projects
  - Shows instructions for Gradle projects
  - "Don't Show Again" option to dismiss for the session
- Auto-apply downloaded sources - after downloading sources, the plugin automatically refreshes and triggers a project reimport so sources are available immediately without restart
- EAP (Early Access Program) channel support - beta testers can opt into pre-release versions

### Fixed
- Step-into from Karate feature lines now correctly opens framework code when skip settings are disabled
- Step-into no longer lands in Karate framework code (StepRuntime.execute) - now correctly stops at user code
- Step-into no longer opens JDK classes - exclusion filters are now applied unconditionally
- Feature Explorer no longer shows duplicate features from build output directories (target/, build/, out/)
- Plugin version now correctly reported in license API calls (was showing "unknown")
- Match expression evaluation now works correctly when stopped in JavaScript code
- Zombie KarateRunner processes are now cleaned up on session start
- Graceful KarateRunner shutdown when IPC connection is lost
- GraalVM internal thread detection for auto-continue during debugging
- SlowOperations EDT violations for PSI lookups
- Debug server classpath resolution with Maven fallback
- Reduced log verbosity by moving debug output to TRACE level

## [0.1.5] - 2026-01-22

### Added
- Log breakpoints - pause execution when specified strings appear in log output (e.g., 'NullPointerException', 'ERROR')
- Syntax highlighting in debug console output (errors in red, warnings in yellow, Karate output in green)
- Clickable file references in feature files with permanent underlines and Cmd/Ctrl+Click navigation:
  - `classpath:path/to/file.feature` - Click to open the referenced file
  - `read('path/to/file.json')` - Works with or without classpath: prefix
  - `read('/path/to/file.json')` - Leading slash paths are supported
  - `read('@tagName')` - Jump to a tag in the current file
  - `file.feature@tagName` - Open file and jump to the specified tag
  - Smart tooltips show "Jump to @tag" for same-file references or "Open file and jump to @tag" for cross-file references
- Log filter setting to hide log lines containing specified strings (e.g., THYMELEAF, HikariPool)

## [0.1.4] - 2026-01-20

### Added
- Basic plugin tests for core components (language, file type, run configuration)
- Dynamic untilBuild calculation - automatically supports +1 year of future IntelliJ releases
- Platform field in license API calls for usage tracking

### Changed
- Extend support back to IntelliJ IDEA 2023.1
- Properly exclude Android Studio and Aqua from marketplace
- CI now shows verified IDE versions in job summary
- Suppress deprecated API warnings with migration TODO for 2026.1+

### Fixed
- Fix plugin verification to only test against released IDE versions

## [0.1.3] - 2026-01-16

### Fixed
- Fix environment selection not being applied to debug sessions

## [0.1.2] - 2026-01-16

### Changed
- Restrict plugin to IntelliJ IDEA only (exclude Android Studio, Aqua)

## [0.1.1] - 2026-01-16

### Fixed
- Fix deprecated API usage for better compatibility
- Suppress GraalJS/Truffle warnings in debug console
- Improve Feature Explorer icons
- Hide [Fix] button for undefined variable errors
- Add one-time pro tip explaining fixes require re-run

## [0.1.0] - 2026-01-15

### Added
- Initial IntelliJ plugin release
- Breakpoint debugging for Karate feature files
- Step-through debugging (step over, step into, step out, continue)
- Variable inspection with expandable tree view
- Variable modification during debug sessions
- Conditional breakpoints with JavaScript expressions
- Match diagnostics with pass/fail highlighting
- Inlay hints showing actual values for failed matches
- Quick fix buttons to update expected values
- Gutter icons for debugging scenarios
- Karate tool window with feature explorer
- Project auto-detection for Maven and Gradle
- Environment and log level switching
- Full syntax highlighting for Karate feature files

