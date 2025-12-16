# Karate Test Fixtures

Test fixtures for developing and testing the Karate Runner VS Code extension.

## Prerequisites

- Java 21+
- Maven
- VS Code with:
  - Karate Runner extension (this project)
  - [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) (for Java debugging)

## Setup

```bash
# Compile the project
mvn compile test-compile
```

## Debugging Feature Files Only

1. Open any `.feature` file
2. Set breakpoints by clicking in the gutter
3. Press **F5** or use "Karate: Debug Feature Only"
4. Step through with F10 (Step Over) or F11 (Step Into)

## Debugging Feature + Java Together (Recommended)

This method allows breakpoints in **both** `.feature` and `.java` files in the same session:

### Quick Start (Compound Launch)

1. **Set breakpoints** in both:
   - `helpers/debug-all-types.feature` (e.g., line 15)
   - `helpers/UserHelper.java` (e.g., inside `createUserPayload()`)

2. **Open** the `.feature` file you want to debug

3. **Launch** "Karate + Java: Full Debug" from the Run/Debug panel

4. **Both debuggers start** - breakpoints work in both file types!

### How It Works

The compound configuration:
1. Launches Karate with `javaDebugPort: 5005` (enables JDWP agent)
2. Automatically attaches VS Code Java debugger to the same JVM
3. Feature breakpoints → handled by Karate debug adapter
4. Java breakpoints → handled by Java debugger (JDWP)

### Manual Two-Step Method

If the compound launch doesn't work:

1. **Start Karate with Java debug enabled:**
   - Select "Karate: Debug with Java (port 5005)"
   - Press F5

2. **Attach Java debugger** (within 5 seconds):
   - Select "Java: Attach (port 5005)"
   - Press F5

## Test Files

| File | Description |
|------|-------------|
| `helpers/UserHelper.java` | Java helper class with methods callable from Karate |
| `helpers/utils.js` | JavaScript utilities loadable via `read()` |
| `helpers/test-data.json` | Sample JSON data for tests |
| `helpers/debug-all-types.feature` | Demo feature using all file types |
| `helpers/DebugRunner.java` | JUnit runner for Maven execution |

## Launch Configurations

| Name | Description |
|------|-------------|
| `Karate: Debug Feature Only` | Debug .feature files only |
| `Karate: Debug with Java (port 5005)` | Debug .feature with Java debug agent |
| `Java: Attach (port 5005)` | Attach to running JVM for Java breakpoints |
| `Karate + Java: Full Debug` | **Compound** - launches both for full debugging |

## Notes

- **Feature breakpoints**: Handled by Karate Runner extension (DAP)
- **Java breakpoints**: Handled by VS Code Java debugger (JDWP)
- **JavaScript in Karate**: Runs in GraalJS; inspect via Karate variables panel
- **JSON files**: Data only; inspect when loaded into variables
- **Compound launch**: Both debuggers must connect; if Java attach fails, restart
