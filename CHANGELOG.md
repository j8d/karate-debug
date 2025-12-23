# Changelog

All notable changes to the Karate Debug extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

