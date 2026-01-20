# Changelog

All notable changes to the Karate Debug IntelliJ plugin will be documented in this file.

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
- 30-day trial with optional GitHub sign-in

