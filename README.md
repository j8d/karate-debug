# Karate Debug

[![VS Code Marketplace](https://img.shields.io/visual-studio-marketplace/v/j8d.karate-debug?label=VS%20Code%20Marketplace&logo=visual-studio-code)](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
[![Installs](https://img.shields.io/visual-studio-marketplace/i/j8d.karate-debug)](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)
[![Rating](https://img.shields.io/visual-studio-marketplace/r/j8d.karate-debug)](https://marketplace.visualstudio.com/items?itemName=j8d.karate-debug)

**A real debugger for Karate `.feature` tests in VS Code.** Breakpoints, variable inspection, hot-swap values, and environment switching—without rewiring your project.

![Karate Debug Demo](https://raw.githubusercontent.com/j8d/karate-debug-marketing/main/demo.gif)

## Get Started in Under 2 Minutes

No complex setup. No configuration files. No sign-up required. Just install and start debugging.

1. **Install** - Search "Karate Debug" in VS Code Extensions
2. **Open** - Navigate to any `.feature` file in your project
3. **Click** - Hit the "Debug Scenario" button above any Scenario
4. **Debug** - Your 30-day free trial starts automatically (no sign-in required)

## Features

### Breakpoint Debugging
Set breakpoints in your `.feature` files and step through your Karate tests line by line. Inspect variables, view request/response data, and understand exactly what's happening at each step.

### Hot Reload Variables
Modify variable values on-the-fly while paused at a breakpoint. Right-click any variable in the Variables panel and set a new value to test different scenarios without restarting your test.

### One-Click Test Execution
CodeLens buttons appear above every Feature and Scenario, letting you debug with a single click - no configuration required.

### Feature Explorer
Browse all your Karate features and scenarios in a dedicated sidebar. Navigate your test suite at a glance and run any test directly from the tree view.

### Environment Switching
Quickly switch between environments (`dev`, `qa`, `stage`, or your custom environments) from the status bar. Your selection persists across sessions.

### Match Expression Diagnostics
Real-time validation of Karate match expressions with inline error highlighting. Get instant feedback on syntax issues with suggested fixes - hover over underlined expressions to see the problem and apply quick fixes with a single click.

### Syntax Highlighting
Full syntax highlighting for the Karate DSL, including Gherkin keywords, JSON/XML payloads, JavaScript expressions, and embedded variables.

### Java Debugging Support
For advanced scenarios, attach a Java debugger simultaneously to debug both your Karate features and underlying Java code.

## Requirements

- **Java 17+** (Java 21 recommended)
- **Maven** project with Karate dependencies
- Tests in `src/test/java` or `src/test/resources`

## Configuration

Configure the extension via VS Code Settings (`Cmd+,` or `Ctrl+,`):

| Setting | Description | Default |
|---------|-------------|---------|
| `karateDebug.environments` | List of available Karate environments | `["dev", "qa", "stage"]` |
| `karateDebug.defaultEnvironment` | Default environment when starting a debug session | `"dev"` |
| `karateDebug.javaHome` | Path to Java installation (auto-detected if empty) | `""` |
| `karateDebug.matchDiagnostics.showPassing` | Show green underlines for passing match statements | `true` |
| `karateDebug.matchDiagnostics.showFailing` | Show red underlines for failing match statements | `true` |
| `karateDebug.matchDiagnostics.showActualValues` | Show actual values and Fix button next to failing matches | `true` |

### Example `settings.json`

```json
{
  "karateDebug.environments": ["local", "dev", "qa", "stage", "prod"],
  "karateDebug.defaultEnvironment": "dev"
}
```

## Launch Configuration

For advanced scenarios, create a `.vscode/launch.json` configuration. When you create a new launch configuration and select "Karate Debug", the extension provides ready-to-use templates.

### Simultaneous Karate and Java Debugging

Debug both your `.feature` files AND underlying Java code in the same session. This is useful when your Karate tests call custom Java helpers or when you need to debug into Karate's internals.

**How it works:**
1. Start the "1. Karate: Start with Java Debug" configuration
2. While it's waiting, start "2. Java: Attach to Karate"
3. Both debuggers are now active - set breakpoints in `.feature` files AND `.java` files

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "karate",
      "request": "launch",
      "name": "1. Karate: Start with Java Debug",
      "feature": "${file}",
      "javaDebugPort": 5006
    },
    {
      "type": "java",
      "request": "attach",
      "name": "2. Java: Attach to Karate",
      "hostName": "localhost",
      "port": 5006,
      "timeout": 30000
    },
    {
      "type": "karate",
      "request": "launch",
      "name": "Karate: Debug Feature Only",
      "feature": "${file}"
    }
  ]
}
```

### Launch Configuration Options

| Property | Description |
|----------|-------------|
| `feature` | Path to the feature file (use `${file}` for current file) |
| `karateEnv` | Karate environment (`karate.env` system property) |
| `javaDebugPort` | Port for Java debugger attachment (enables simultaneous Java/Karate debugging) |

## License Management

### Status Bar
The license status bar shows your current subscription status:
- **"Trial: Xd left"** - Active trial with days remaining
- **"Karate Debug Pro"** - Active Pro subscription
- **"Trial Expired"** - Trial ended, click to purchase

### Commands
- `Karate Debug: Upgrade to Pro` - Purchase a Pro subscription
- `Karate Debug: Sign In with GitHub` - Link your account (only needed for purchase)
- `Karate Debug: Sign Out` - Sign out of your account
- `Karate Debug: Manage Subscription` - Manage billing and subscription
- `Karate Debug: License Info` - View current license status

### How Licensing Works
- **Trial starts automatically** when you install the extension—no sign-in required
- **GitHub sign-in is only needed when you purchase** a Pro subscription
- **Your license activates on up to 5 machines** after purchase

## Pricing

**Free Trial** - $0 for 30 days with full access to all features. No sign-up or credit card required.

**Karate Debug Pro** - $29.99/month for unlimited debugging sessions, priority support, up to 5 machine activations, and early access to new features.

## Privacy and Security

- **Your code stays local.** All debugging happens on your machine. We never see, access, or store your code, tests, or test data.
- **No sign-in required for trial.** Your trial starts automatically when you install. GitHub sign-in is only needed when you purchase.
- **Works offline.** Once activated, Karate Debug works fully offline. An internet connection is only needed for initial activation and periodic license checks.

## FAQ

**What are the system requirements?**
Java 17+ (21 recommended), a Maven project with Karate dependencies, and VS Code. Tests should be in `src/test/java` or `src/test/resources`.

**Can I use it on multiple machines?**
Pro subscribers can activate on up to 5 machines—perfect for work laptop, home desktop, and CI environments.

**What happens after the 30-day trial?**
Subscribe to Pro for $29.99/month to continue using all features. No credit card is required to start the trial.

## Feedback

Have a feature request or found a bug? Email me at [ryan@karatedebug.com](mailto:ryan@karatedebug.com).

## License

This extension is proprietary software. See the [full license terms](https://www.karatedebug.com/license).

## Acknowledgments

- [Karate](https://github.com/karatelabs/karate) - The best API testing framework
