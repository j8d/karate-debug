# Karate Debug

[![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

A powerful VS Code extension for debugging [Karate](https://github.com/karatelabs/karate) tests. Set breakpoints directly in your `.feature` files, step through scenarios including Java and JavaScript code, inspect variables, and run tests with a single click.

> **This is a new extension!** Feature requests, bug reports, and any other feedback or contributions are welcome! Feel free to [open an issue](https://github.com/j8d/karate-debug/issues).

## Features

### Breakpoint Debugging for Karate, Java and JavaScript
Debug Karate, Java and JavaScript code in the same debugging session. Set breakpoints in your `.feature` files, Java files and JavaScript files. Step through your Karate tests line by line. Inspect variables, view request/response data, and understand exactly what's happening at each step.

### Hot Reload Variables
Modify variable values on-the-fly while paused at a breakpoint. Right-click any variable in the Variables panel and set a new value to test different scenarios without restarting your test.

### One-Click Test Execution
CodeLens buttons appear above every Feature and Scenario, letting you debug with a single click—no configuration required.

### Feature Explorer
Browse all your Karate features and scenarios in a dedicated sidebar. Navigate your test suite at a glance and run any test directly from the tree view.

### Environment Switching
Quickly switch between environments (`dev`, `qa`, `stage`, or your custom environments) from the status bar. Your selection persists across sessions.

### Syntax Highlighting
Full syntax highlighting for the Karate DSL, including Gherkin keywords, JSON/XML payloads, JavaScript expressions, and embedded variables.

### File Navigation
Clickable file references throughout your feature files. File paths appear as underlined links - Cmd+Click (Mac) or Ctrl+Click (Windows/Linux) to navigate directly to the referenced file or tag:
- `classpath:path/to/file.feature` - Opens the referenced file
- `read('path/to/file.json')` - Works with or without classpath: prefix
- `read('@tagName')` - Jumps to a tag in the current file
- `file.feature@tagName` - Opens the file and jumps to the specified tag

### Log Breakpoints
Pause execution when specific strings appear in log output. Useful for catching exceptions or specific error messages without setting traditional breakpoints - just specify strings like "NullPointerException" or "ERROR" to break on. Configure via the `karateDebug.logBreakpoints` setting.

### Log Filtering
Hide noisy log output by configuring exclude patterns. Filter out verbose framework messages (like HikariPool, Thymeleaf) to focus on what matters during debugging. Configure via the `karateDebug.logFilter.exclude` setting.

## Requirements

- **Java 17+** (Java 21 recommended for best compatibility)
- **Maven** project with Karate dependencies
- Tests located in `src/test/java` or `src/test/resources`

## Quick Start

1. **Install** the extension from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
2. **Open** a Maven project containing Karate tests
3. **Open** any `.feature` file
4. **Click** `Debug Feature` or `Debug Scenario` above your test

That's it! The debugger will start, and you can set breakpoints, step through code, and inspect variables.

## Configuration

Configure the extension via VS Code Settings (`Cmd+,` or `Ctrl+,`):

| Setting | Description | Default |
|---------|-------------|---------|
| `karateDebug.environments` | List of available Karate environments | `["dev", "qa", "stage"]` |
| `karateDebug.defaultEnvironment` | Default environment when starting a debug session | `"dev"` |
| `karateDebug.javaHome` | Path to Java installation (auto-detected if empty) | `""` |
| `karateDebug.matchDiagnostics.showPassing` | Show green underlines for passing match statements | `true` |
| `karateDebug.matchDiagnostics.showFailing` | Show red underlines for failing match statements | `true` |
| `karateDebug.matchDiagnostics.showActualValues` | Show actual values and Fix button next to failing match statements | `true` |
| `karateDebug.logLevel` | Log level for Karate Debug output (`error`, `warn`, `info`, `debug`, `trace`) | `"info"` |
| `karateDebug.logFilter.exclude` | Array of strings - log lines containing any of these strings will be hidden from output | `["THYMELEAF"]` |
| `karateDebug.logBreakpoints` | Array of strings - pause execution when any of these strings appear in log output | `[]` |

### Example `settings.json`

```json
{
  "karateDebug.environments": ["local", "dev", "qa", "stage", "prod"],
  "karateDebug.defaultEnvironment": "dev",
  "karateDebug.logFilter.exclude": ["THYMELEAF", "HikariPool", "PoolBase"],
  "karateDebug.logBreakpoints": ["NullPointerException", "ERROR", "FATAL"]
}
```

## Launch Configuration

The extension automatically creates a default launch configuration when you first debug a feature file. For advanced scenarios, you can customize the `.vscode/launch.json` configuration.

### Default Configuration

```json
{
  "type": "karate",
  "request": "launch",
  "name": "Karate: Debug",
  "feature": "${file}",
  "enableJavaDebugging": true,
  "enableJsDebugging": true,
  "skipJdkClasses": false,
  "skipKarateFramework": false,
  "skipKarateDependencies": false
}
```

### Launch Configuration Options

| Property | Description | Default |
|----------|-------------|---------|
| `feature` | Path to the feature file to debug (use `${file}` for current file) | (required) |
| `karateEnv` | Karate environment (`karate.env` system property) | `"dev"` |
| `karateOptions` | Additional Karate CLI options | `""` |
| `enableJavaDebugging` | Enable Java debugging - set breakpoints in Java code and step through Java methods | `true` |
| `enableJsDebugging` | Enable JavaScript debugging - set breakpoints in .js files and step through JavaScript functions | `true` |
| `javaDebugPort` | Port for Java debugger (JDWP). When set, enables attaching an external Java debugger | `0` (auto) |
| `jsDebugPort` | Port for JavaScript debugger (Chrome DevTools Protocol) | `0` (auto) |

### Step Filtering Options

Control which code is automatically skipped when stepping through Java code:

| Property | Description | Default |
|----------|-------------|---------|
| `skipJdkClasses` | Skip JDK core classes (java.*, javax.*, jdk.*, sun.*, com.sun.*) when stepping | `true` |
| `skipKarateFramework` | Skip Karate framework classes (com.intuit.karate.*) when stepping | `true` |
| `skipKarateDependencies` | Skip Karate's third-party dependencies (jsonpath, netty, slf4j, etc.) when stepping | `true` |

When step filtering is enabled, stepping into framework code will automatically step out and return to user code.

- Set `skipKarateFramework` to `false` to step into Karate source code
- Set `skipKarateDependencies` to `false` to also step through Karate's internal libraries

### Java Source Options

| Property | Description | Default |
|----------|-------------|---------|
| `javaSourcePaths` | Additional directories containing Java source files for inline variable display | `[]` |
| `autoDetectJdkSources` | Automatically detect and use JDK source files (src.zip) from the Java installation | `true` |


## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines, or [open an issue](https://github.com/j8d/karate-debug/issues) to discuss your ideas.


## License

This extension is open-source software licensed under the [Apache License 2.0](LICENSE).

## Acknowledgments

- [Karate](https://github.com/karatelabs/karate) - My favorite automation testing framework
