# Karate Debug

[![VS Code Marketplace](https://img.shields.io/visual-studio-marketplace/v/j8d.karate-debug?label=VS%20Code%20Marketplace&logo=visual-studio-code)](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
[![GitHub Release](https://img.shields.io/github/v/release/j8d/karate-debug?logo=github)](https://github.com/j8d/karate-debug/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A powerful VS Code extension for debugging [Karate](https://github.com/karatelabs/karate) API tests. Set breakpoints directly in your `.feature` files, step through scenarios, inspect variables, and run tests with a single click.

## ✨ Features

### 🔴 Breakpoint Debugging
Set breakpoints in your `.feature` files and step through your Karate tests line by line. Inspect variables, view request/response data, and understand exactly what's happening at each step.

### ▶️ One-Click Test Execution
CodeLens buttons appear above every Feature and Scenario, letting you debug with a single click—no configuration required.

### 🌳 Feature Explorer
Browse all your Karate features and scenarios in a dedicated sidebar. Navigate your test suite at a glance and run any test directly from the tree view.

### 🔄 Environment Switching
Quickly switch between environments (`dev`, `qa`, `stage`, or your custom environments) from the status bar. Your selection persists across sessions.

### 🎨 Syntax Highlighting
Full syntax highlighting for the Karate DSL, including Gherkin keywords, JSON/XML payloads, JavaScript expressions, and embedded variables.

### ☕ Java Debugging Support
For advanced scenarios, attach a Java debugger simultaneously to debug both your Karate features and underlying Java code.

## 📋 Requirements

- **Java 17+** (Java 21 recommended for best compatibility)
- **Maven** project with Karate dependencies
- Tests located in `src/test/java` or `src/test/resources`

## 🚀 Quick Start

1. **Install** the extension from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
2. **Open** a Maven project containing Karate tests
3. **Open** any `.feature` file
4. **Click** `▶ Debug Feature` or `▶ Debug Scenario` above your test

That's it! The debugger will start, and you can set breakpoints, step through code, and inspect variables.

## ⚙️ Configuration

Configure the extension via VS Code Settings (`Cmd+,` or `Ctrl+,`):

| Setting | Description | Default |
|---------|-------------|---------|
| `karateDebug.environments` | List of available Karate environments | `["dev", "qa", "stage"]` |
| `karateDebug.defaultEnvironment` | Default environment when starting a debug session | `"dev"` |
| `karateDebug.javaHome` | Path to Java installation (auto-detected if empty) | `""` |

### Example `settings.json`

```json
{
  "karateDebug.environments": ["local", "dev", "qa", "stage", "prod"],
  "karateDebug.defaultEnvironment": "dev"
}
```

## 🔧 Launch Configuration

For advanced scenarios, create a `.vscode/launch.json` configuration:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "karate",
      "request": "launch",
      "name": "Debug Current Feature",
      "feature": "${file}",
      "karateEnv": "dev"
    },
    {
      "type": "karate",
      "request": "launch",
      "name": "Debug with Java Debugger",
      "feature": "${file}",
      "karateEnv": "dev",
      "javaDebugPort": 5005
    }
  ]
}
```

### Launch Configuration Options

| Property | Description |
|----------|-------------|
| `feature` | Path to the feature file (use `${file}` for current file) |
| `karateEnv` | Karate environment (`karate.env` system property) |
| `javaDebugPort` | Port for Java debugger attachment (enables dual debugging) |

## 🏗️ Building from Source

```bash
# Clone the repository
git clone https://github.com/j8d/karate-debug.git
cd karate-debug

# Build the Java debug server
cd debug-server && mvn clean package -q && cd ..

# Install dependencies and compile
npm install
npm run compile

# Package the extension
npm run package

# Install locally
code --install-extension karate-debug-*.vsix
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a [Pull Request](https://github.com/j8d/karate-debug/pulls) or open an [Issue](https://github.com/j8d/karate-debug/issues).

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [Karate](https://github.com/karatelabs/karate) - The powerful API testing framework
- [VS Code Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) - Debug adapter implementation
