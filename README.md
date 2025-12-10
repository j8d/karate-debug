# Karate Runner

A lightweight VS Code extension for debugging Karate API tests.

## Download

**[Download the latest release](https://github.com/resmed/patientcore-karate-vscode/releases/latest)**

Download the `.vsix` file and install it in VS Code:
```bash
code --install-extension karate-runner-x.x.x.vsix
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

1. Update the version in `package.json`
2. Commit your changes
3. Create and push a tag:
   ```bash
   git tag v0.1.2
   git push origin v0.1.2
   ```
4. GitHub Actions will automatically build and attach the `.vsix` to the release

## License

MIT
