# Karate Debug - IntelliJ Plugin

A debugger for Karate `.feature` tests in IntelliJ IDEA. Set breakpoints, step through tests, and inspect variables.

## Status

**Phase 1 Complete** - Basic debugging functionality is working:
- Project detection (Maven/Gradle with Karate dependencies)
- Syntax highlighting for `.feature` files
- Run/Debug gutter icons
- Breakpoint support with stepping
- Variable inspection
- Feature Explorer tool window

## Requirements

- IntelliJ IDEA 2024.2+
- Java 17+ (Java 21 recommended)
- Maven or Gradle project with Karate dependencies

## Development

### Build the plugin

```bash
cd intellij
./gradlew build
```

### Run in development mode

```bash
cd intellij
./gradlew runIde
```

This launches a sandboxed IntelliJ instance with the plugin installed.

### Install from disk

1. Build the plugin ZIP:
   ```bash
   ./gradlew buildPlugin
   ```

2. Find the ZIP at `build/distributions/karate-debug-intellij-0.1.0.zip`

3. In IntelliJ: Settings -> Plugins -> Gear icon -> Install Plugin from Disk

## Architecture

The plugin uses the shared debug server (`shared/debug-server/`) which implements the Debug Adapter Protocol (DAP). The IntelliJ plugin acts as a DAP client, bridging IntelliJ's XDebugger API to the DAP server.

### Key Components

- `KarateDebugProcess` - XDebugProcess implementation bridging to DAP
- `KarateDapClient` - DAP protocol client handling JSON-RPC communication
- `KarateBreakpointHandler` - Manages breakpoint synchronization with DAP server
- `KarateProjectService` - Detects Karate projects, resolves classpath
- `KarateRunConfiguration` - Run/Debug configuration for Karate features

## License

Proprietary - See root LICENSE file.

