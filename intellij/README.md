# Karate Debug for IntelliJ IDEA

**A real debugger for Karate `.feature` tests in IntelliJ IDEA.** Breakpoints, variable inspection, hot-swap values, and environment switching - without rewiring your project.

## Get Started in Under 2 Minutes

No complex setup. No configuration files. No sign-up required. Just install and start debugging.

1. **Install** - Search "Karate Debug" in IntelliJ Plugins (Settings > Plugins > Marketplace)
2. **Open** - Navigate to any `.feature` file in your project
3. **Click** - Hit the debug gutter icon next to any Feature or Scenario
4. **Debug** - Your 30-day free trial starts automatically (no sign-in required)

## Features

### Breakpoint Debugging
Set breakpoints in your `.feature` files and step through your Karate tests line by line. Inspect variables, view request/response data, and understand exactly what's happening at each step.

### Conditional Breakpoints
Right-click on any breakpoint to add a condition. The debugger will only pause when the condition evaluates to true - perfect for debugging specific iterations in loops or catching edge cases.

### Hot Reload Variables
Modify variable values on-the-fly while paused at a breakpoint. Double-click or press F2 on any variable in the Variables panel to set a new value and test different scenarios without restarting your test.

### One-Click Test Execution
Gutter icons appear next to every Feature and Scenario, letting you debug with a single click - no configuration required.

### Karate Tool Window
Browse all your Karate features and scenarios in a dedicated tool window. Navigate your test suite at a glance and run any test directly from the tree view.

### Environment Switching
Quickly switch between environments (`dev`, `qa`, `stage`, or your custom environments) from the run configuration or settings. Your selection persists across sessions.

### Match Expression Diagnostics
Real-time validation of Karate match expressions with inline error highlighting during debugging. Get instant feedback on match failures with actual vs expected values displayed as inlay hints.

### Syntax Highlighting
Full syntax highlighting for the Karate DSL, including Gherkin keywords, JSON/XML payloads, JavaScript expressions, Java class references, and embedded variables.

## Requirements

- **IntelliJ IDEA 2024.1+** (Community or Ultimate)
- **Java 17+** (Java 21 recommended)
- **Maven** project with Karate dependencies
- Tests in `src/test/java` or `src/test/resources`

## Configuration

Configure the plugin via Settings > Tools > Karate Debug:

| Setting | Description | Default |
|---------|-------------|---------|
| Karate Environment | Default environment for debug sessions | `dev` |
| Available Environments | Comma-separated list of environments | `dev,qa,stage` |
| Show Passing Matches | Highlight passing match statements during debug | `true` |
| Show Failing Matches | Highlight failing match statements during debug | `true` |
| Show Actual Values | Display actual values next to failing matches | `true` |

## Run Configuration

For advanced scenarios, create a Karate Debug run configuration:

1. Run > Edit Configurations > + > Karate Debug
2. Select the feature file
3. Optionally specify a scenario name to run only that scenario
4. Set the Karate environment

### Run Configuration Options

| Property | Description |
|----------|-------------|
| Feature File | Path to the `.feature` file to debug |
| Scenario | Optional scenario name (runs all if empty) |
| Environment | Karate environment (`karate.env` system property) |

## License Management

### Status Bar
The license status appears in the IntelliJ status bar:
- **"Trial: Xd left"** - Active trial with days remaining
- **"Karate Debug Pro"** - Active Pro subscription
- **"Trial Expired"** - Trial ended, click to purchase

### How Licensing Works
- **Trial starts automatically** when you install the plugin - no sign-in required
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
IntelliJ IDEA 2024.1+, Java 17+ (21 recommended), a Maven project with Karate dependencies. Tests should be in `src/test/java` or `src/test/resources`.

**Can I use it on multiple machines?**
Pro subscribers can activate on up to 5 machines - perfect for work laptop, home desktop, and CI environments.

**What happens after the 30-day trial?**
Subscribe to Pro for $29.99/month to continue using all features. No credit card is required to start the trial.

**Is there also a VS Code version?**
Yes! Search "Karate Debug" in the VS Code Extensions marketplace.

## Feedback

Have a feature request or found a bug? Email us at [ryan@karatedebug.com](mailto:ryan@karatedebug.com).

## License

This plugin is proprietary software. See the [full license terms](https://www.karatedebug.com/license).

## Acknowledgments

- [Karate](https://github.com/karatelabs/karate) - The best API testing framework
