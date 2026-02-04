# Changelog

All notable changes to the Karate Debug extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7.0] - 2026-02-04

### Added
- Log breakpoints - pause execution when specified strings appear in log output (e.g., 'NullPointerException', 'ERROR'). Configure via `karateDebug.logBreakpoints` setting.
- Clickable file references in feature files with Cmd/Ctrl+Click navigation:
  - `classpath:path/to/file.feature` - Click to open the referenced file
  - `read('path/to/file.json')` - Works with or without classpath: prefix
  - `read('/path/to/file.json')` - Leading slash paths are supported
  - `read('@tagName')` - Jump to a tag in the current file
  - `file.feature@tagName` - Open file and jump to the specified tag
  - Smart tooltips show "Jump to @tag" for same-file references or "Open file and jump to @tag" for cross-file references
- Log filter setting to hide log lines containing specified strings. Configure via `karateDebug.logFilter.exclude` setting. Defaults to filtering THYMELEAF logs.
- Polyglot debugging with unified Karate, JavaScript, and Java debugging in a single session
- Inline variable values displayed next to code during debugging
- Step filtering options to skip JDK, Karate framework, and dependency classes

### Changed
- Debug sessions now automatically open the feature file and scroll to the scenario line
- JavaScript debugging is now enabled by default with bundled GraalVM DAP tool
- Launch configuration migration automatically updates old Karate configurations to the new format

### Fixed
- IpcServer now logs error when message queue is full instead of silently dropping messages
- DapClient now properly catches NumberFormatException when parsing Content-Length header
- DapClient removed unused `headerBuilder` variable
- ChildProcessManager now handles NumberFormatException when parsing JDWP, DAP, and IPC ports from child process output
- Removed unused `category` parameter from `forwardToDebugConsole` method
- Removed dead code: unused `outputSender` field and related methods in ChildProcessManager and DebugCoordinator
- Port cleanup now uses graceful shutdown (SIGTERM) before force kill (SIGKILL) and skips on Windows where lsof is unavailable

## [0.6.11] - 2026-01-22

### Changed
- Match diagnostics now only show for the current scenario being debugged (consistent with IntelliJ plugin)
- Add platform field to license API calls for usage tracking by IDE

### Updated
- Dependency updates: logback-classic 1.5.25, gson 2.13.2, npm dependencies

## [0.6.8] - 2026-01-13

### Added
- Error message inlay hints for match expressions with syntax errors, unknown matchers, or type errors
- Support for type mismatch detection when actual value is an array/object (e.g., matching array against #string)

### Changed
- Error messages now display without "actual:" prefix and without [Fix] button since they cannot be auto-fixed
- Simplified error message prefixes for better readability (e.g., "invalid syntax:", "unknown matcher:")

## [0.6.7] - 2026-01-05

### Changed
- Trial now starts automatically when you install the extension - no sign-in required
- GitHub sign-in is only needed when purchasing a Pro subscription
- Purchase flow now triggers GitHub authentication automatically if not signed in

### Fixed
- Trial days remaining now displays consistently between status bar and info popup

## [0.6.6] - 2025-01-02

### Removed
- Removed match diagnostics status bar indicator for cleaner UI

## [0.6.5] - 2025-01-02

### Added
- New `showActualValues` setting to control inlay hints independently from decorations
- Match diagnostics settings now documented in README

### Changed
- Brighter green/red colors for match diagnostics underlines
- Use `DiagnosticSeverity.Hint` to avoid duplicate squiggly underlines

### Fixed
- Fixed logout not updating status - clicking status bar after logout now correctly shows "Sign In" option

## [0.6.4] - 2025-01-01

### Added
- CI workflow with linting and automated tests on every push/PR
- ESLint configuration for TypeScript code quality
- License page at https://www.karatedebug.com/license

### Changed
- Release workflow now automatically publishes to VS Code Marketplace
- Updated copyright to 2025-2026
- Updated contact email to ryan@karatedebug.com
- License now correctly states 30-day trial period

### Fixed
- Fixed ESLint warnings with proper error type handling
- Fixed test extension ID for CI compatibility

## [0.6.3] - 2024-12-29

### Changed
- Updated README with trust-building content: privacy assurances, clearer pricing, FAQ section
- Added social proof badges (installs, rating)
- Streamlined getting started flow

## [0.6.2] - 2024-12-29

### Changed
- Removed demo.gif from package (now hosted externally) - significantly reduces extension size

## [0.6.1] - 2024-12-29

### Fixed
- Fixed contact email in README

## [0.6.0] - 2024-12-29

### Changed
- Updated contact email to ryan@karatedebug.com
- Improved marketplace categories and keywords for API testing and automation discovery
- Fixed demo GIF URL to use public hosting

## [0.5.4] - 2024-12-29

### Fixed
- Demo GIF now displays correctly on VS Code Marketplace

## [0.5.3] - 2024-12-29

### Fixed
- Hot reload variables now works correctly - variable changes are applied on the Karate execution thread to handle thread-local JS engine bindings

### Added
- Demo GIF in README showcasing extension features

## [0.5.1] - 2024-12-23

### Fixed
- Fresh installs now correctly show "Sign In" instead of "Trial Expired"
- Browser tab auto-closes after OAuth redirect
- Logout now properly clears trial state

## [0.5.0] - 2024-12-23

### Added
- GitHub authentication for license management
- 30-day free trial for new users
- Stripe integration for Pro subscriptions
- License status bar indicator showing trial days remaining
- Machine activation tracking (up to 5 machines per license)

### Changed
- Debug features now require active trial or Pro subscription

## [0.4.0] - 2024-12-22

### Added
- Hot Reload Variables: Modify variable values at runtime while paused at a breakpoint
- Right-click any variable in the Variables panel and select "Set Value" to change its value
- Supports null, boolean, numbers, strings, and JSON objects/arrays

## [0.3.3] - 2024-12-18

### Changed
- Further reduced verbose logging (DAP command handling now at DEBUG level)
- Cleaner Output panel with only essential startup and completion messages

## [0.3.2] - 2024-12-18

### Fixed
- Karate test output now appears in both Debug Console and Output panel
- Improved stdout/stderr redirect to preserve output for both views

### Changed
- Reduced verbose logging during test execution for cleaner output

## [0.3.1] - 2024-12-18

### Added
- Simultaneous Karate and Java debugging support with preconfigured launch templates
- "Support the Project" section with Buy Me A Coffee link

### Changed
- Improved launch.json templates with three configurations:
  - "1. Karate: Start with Java Debug" - Karate debugging with Java debug port
  - "2. Java: Attach to Karate" - Attach Java debugger to running session
  - "Karate: Debug Feature Only" - Simple Karate-only debugging

## [0.3.0] - 2024-12-18

### Added
- Professional README with comprehensive documentation
- CHANGELOG for version history tracking
- VS Code Marketplace metadata and keywords

### Changed
- Renamed output channel from "Karate Runner" to "Karate Debug"
- Test output now displays in VS Code Debug Console instead of separate output panel

### Removed
- Obsolete Backstage/TechDocs configuration files
- Legacy shell scripts replaced by integrated debug server

## [0.2.1] - 2024-12-17

### Added
- Configurable environments via `karateDebug.environments` setting
- Default environment setting via `karateDebug.defaultEnvironment`
- Breakpoints support for `.feature` files

### Fixed
- Debug server now properly handles VS Code probe connections
- Debug session lifecycle improvements

## [0.2.0] - 2024-12-17

### Changed
- Migrated project from `resmed` organization to `j8d`
- Renamed Java package from `com.resmed.karate.debug` to `com.j8d.karate.debug`
- Updated all repository references

### Added
- GitHub Actions release workflow
- MIT License

## [0.1.x] - Previous Releases

### Features
- Breakpoint debugging in `.feature` files
- CodeLens buttons for one-click debugging
- Feature Explorer sidebar
- Environment switching from status bar
- Karate DSL syntax highlighting
- Java debugger attachment support

