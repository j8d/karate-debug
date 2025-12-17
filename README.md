# Karate Debugger

A lightweight VS Code extension for debugging Karate API tests.

## Installation

### From VS Code Marketplace (Recommended)

**[Install from Marketplace](https://marketplace.visualstudio.com/items?itemName=karate-runner.karate-debugger)**

Or install via command line:
```bash
code --install-extension karate-runner.karate-debugger
```

Or search for `karate-debugger` in VS Code Extensions (`Cmd+Shift+X`).

### From GitHub Releases

Download the `.vsix` file from [GitHub Releases](https://github.com/resmed/patientcore-karate-vscode/releases/latest) and install:
```bash
code --install-extension karate-debugger-x.x.x.vsix
```

## Features

- **Debug Karate tests** with breakpoints and step-through debugging
- **CodeLens buttons** - Click `▶ Debug Feature` or `▶ Debug Scenario` directly in your `.feature` files
- **Feature Explorer** - Browse all features and scenarios in the sidebar
- **Environment selector** - Switch between `dev`, `qa`, and `stage` from the status bar
- **Syntax highlighting** - Full Karate DSL syntax support

## Requirements

- Java 11+ installed and available on PATH
- Maven project with Karate dependencies

## Getting Started

1. Install this extension
2. Open a Maven project containing Karate tests
3. Open any `.feature` file
4. Click `▶ Debug Feature` or `▶ Debug Scenario` above your tests

## Configuration

| Setting | Description | Default |
|---------|-------------|---------|
| `karateRunner.karateEnv` | Default Karate environment | `dev` |
| `karateRunner.javaHome` | Path to Java home | System default |

## Building from Source

```bash
npm install
npm run compile
npm run package
```

Install the generated `.vsix` file:
```bash
code --install-extension karate-runner-x.x.x.vsix
```

## Releasing a New Version

1. Update the version: `npm version patch`
2. Commit and tag:
   ```bash
   git add package.json package-lock.json
   git commit -m "chore: bump version to x.x.x"
   git tag vx.x.x
   git push && git push origin vx.x.x
   ```
3. Publish to Marketplace:
   ```bash
   npx vsce publish
   ```

## License

MIT
