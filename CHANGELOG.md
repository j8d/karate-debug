# Changelog

All notable changes to the Karate Debug extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

