# Karate Debug

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![GitHub stars](https://img.shields.io/github/stars/j8d/karate-debug.svg?style=social&label=Star)](https://github.com/j8d/karate-debug)
[![VS Code Version](https://img.shields.io/visual-studio-marketplace/v/j8d.karate-debug)](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
[![IntelliJ Plugin](https://img.shields.io/jetbrains/plugin/v/26605-karate-debug)](https://plugins.jetbrains.com/plugin/26605-karate-debug)

A debugging solution for Karate API tests supporting both VS Code and IntelliJ IDEA.

## Project Structure

```
karate-debug/
├── shared/debug-server/     # Java DAP server (shared by both IDEs)
├── vscode/                  # VS Code extension (TypeScript)
├── intellij/                # IntelliJ plugin (Java/Kotlin Gradle)
├── test-fixtures/           # Sample Karate project for testing
└── .github/workflows/       # CI/CD workflows
```

## Quick Start

### Prerequisites
- Node.js 20+
- Java 21+
- Maven 3.6+

### Build Everything
```bash
# Build shared debug server
cd shared/debug-server && mvn clean package -q

# Copy JAR to both extensions
cp shared/debug-server/target/karate-debug-server-1.0.0.jar vscode/resources/
cp shared/debug-server/target/karate-debug-server-1.0.0.jar intellij/src/main/resources/

# Build VS Code extension
cd vscode && npm install && npm run compile && npm run package

# Build IntelliJ plugin
cd intellij && ./gradlew buildPlugin
```

### Quick Rebuilds
```bash
# VS Code only
cd vscode && npm run compile

# IntelliJ only
cd intellij && ./gradlew buildPlugin

# Run IntelliJ sandbox
cd intellij && ./gradlew runIde
```

## Releasing

Releases are triggered manually via GitHub Actions:

1. Go to **Actions > Release**
2. Click **Run workflow**
3. Select platform: `vscode`, `intellij`, or `both`
4. Optionally specify version (defaults to current)

## Documentation

| Document | Location |
|----------|----------|
| VS Code README | `vscode/README.md` |
| VS Code Changelog | `vscode/CHANGELOG.md` |
| IntelliJ README | `intellij/README.md` |
| IntelliJ Changelog | `intellij/CHANGELOG.md` |
| Internal Dev Guide | `.augment-guidelines` |

## Links

- **VS Code Marketplace**: https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug
- **JetBrains Marketplace**: https://plugins.jetbrains.com/plugin/26605-karate-debug
- **GitHub Repository**: https://github.com/j8d/karate-debug
- **Report Issues**: https://github.com/j8d/karate-debug/issues
