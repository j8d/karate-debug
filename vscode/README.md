# karate-debug

A Visual Studio Code extension for Karate.

## Getting Started

1. Install the extension from the Visual Studio Code Marketplace.
2. Open your Karate project in Visual Studio Code.
3. Start coding your tests!

## Features

- Syntax highlighting for .feature files.
- Debugging support for Karate.

## Configuration

The extension provides the following configuration settings:

| Setting | Description | Default |
|---------|-------------|---------|
| `karateDebug.environments` | List of available Karate environments | `["dev", "qa", "stage"]` |
| `karateDebug.defaultEnvironment` | Default Karate environment to use | `"dev"` |
| `karateDebug.javaHome` | Path to Java home (uses system default if empty) | `""` |
| `karateDebug.matchDiagnostics.showPassing` | Show green underlines for passing match statements | `true` |
| `karateDebug.matchDiagnostics.showFailing` | Show red underlines for failing match statements | `true` |
| `karateDebug.matchDiagnostics.showActualValues` | Show actual values and Fix button next to failing match statements | `true` |
| `karateDebug.logLevel` | Log level for Karate Debug output (`error`, `warn`, `info`, `debug`, `trace`) | `"info"` |
| `karateDebug.logFilter.exclude` | Array of strings – log lines containing any of these strings will be hidden from output | `[]` |
| `karateDebug.logBreakpoints` | Array of strings – pause execution when any of these strings appear in log output (e.g., "NullPointerException", "ERROR") | `[]` |

## License

BSD-3-Clause License