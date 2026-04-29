# Karate Debug for IntelliJ IDEA

**A real debugger for Karate `.feature` tests in IntelliJ IDEA.** Breakpoints, variable inspection, hot-swap values, and environment switching - without rewiring your project.

## Get Started in Under 2 Minutes

No complex setup. No configuration files. No sign-up required. Just install and start debugging.

1. **Install** - Search "Karate Debug" in IntelliJ Plugins (Settings > Plugins > Marketplace)
2. **Open** - Navigate to any `.feature` file in your project
3. **Click** - Hit the debug gutter icon next to any Feature or Scenario
4. **Debug** - Start debugging immediately - it's free and open-source!

## Features

### Breakpoint Debugging for Karate, Java and JavaScript
Debug Karate, Java and JavaScript code in the same debugging session. Set breakpoints in your `.feature` files, Java files and JavaScript files. Step through your Karate tests line by line. Inspect variables, view request/response data, and understand exactly what's happening at each step.

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

### File Navigation
Clickable file references throughout your feature files. File paths appear as underlined links - Cmd+Click (Mac) or Ctrl+Click (Windows/Linux) to navigate directly to the referenced file or tag:
- `classpath:path/to/file.feature` - Opens the referenced file
- `read('path/to/file.json')` - Works with or without classpath: prefix
- `read('@tagName')` - Jumps to a tag in the current file
- `file.feature@tagName` - Opens the file and jumps to the specified tag

### Log Filtering
Hide noisy log output by configuring exclude patterns. Filter out verbose framework messages (like HikariPool, Thymeleaf) to focus on what matters during debugging.

### Log Breakpoints
Pause execution when specific strings appear in log output. Useful for catching exceptions or specific error messages without setting traditional breakpoints - just specify strings like "NullPointerException" or "ERROR" to break on.

### Step Filtering [Experimental]
Control which code is automatically skipped when stepping through Java code. By default, the debugger skips JDK classes, Karate framework classes, and third-party dependencies so you can focus on your own code. When step filtering is enabled, stepping into framework code will automatically step out and return to user code.

Configure these options in Settings > Tools > Karate Debug:
- **Show JDK classes** - Check to step into JDK core classes (java.*, javax.*, jdk.*, sun.*, com.sun.*)
- **Show Karate framework classes** - Check to step into Karate source code (com.intuit.karate.*)
- **Show Karate dependencies** - Check to step through Karate's internal libraries (jsonpath, netty, slf4j, etc.)

### Missing Sources Notification
When stepping into library code that lacks source files, the debugger shows a notification with a "Download Sources" button. For Maven projects, this runs `mvn dependency:sources` to fetch the missing sources automatically.

### Syntax Highlighting
Full syntax highlighting for the Karate DSL, including Gherkin keywords, JSON/XML payloads, JavaScript expressions, Java class references, and embedded variables.

## Requirements

- **IntelliJ IDEA 2023.1+** (Community or Ultimate)
- **Java 21+**
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
| Show Actual Values | Display actual values next to failing matches with a [Fix] button to replace the expected value | `true` |
| Show JDK Classes | Step into JDK classes (java.*, javax.*, jdk.*, sun.*, com.sun.*) | `false` |
| Show Karate Framework | Step into Karate framework classes (com.intuit.karate.*) | `false` |
| Show Karate Dependencies | Step into Karate's third-party dependencies (jsonpath, netty, slf4j, etc.) | `false` |

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

## Privacy and Security

- **Your code stays local.** All debugging happens on your machine - your code, tests, and test data never leave your computer.
- **Works offline.** Karate Debug works fully offline once installed.

## FAQ

**What are the system requirements?**
IntelliJ IDEA 2024.1+, Java 17+ (21 recommended), a Maven project with Karate dependencies. Tests should be in `src/test/java` or `src/test/resources`.

**Can I use it on multiple machines?**
Yes! Karate Debug is free and open-source - use it on as many machines as you need.

**Is there also a VS Code version?**
Yes! Search "Karate Debug" in the VS Code Extensions marketplace.

## Feedback

Have a feature request or found a bug? Please [open an issue](https://github.com/j8d/karate-debug/issues) on GitHub.

## License

This plugin is open-source software licensed under the [Apache License 2.0](../LICENSE).

## Acknowledgments

- [Karate](https://github.com/karatelabs/karate) - The best API testing framework
