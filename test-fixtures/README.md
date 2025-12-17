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

1. **Set breakpoints** in both:
   - `helpers/debug-all-types.feature` (e.g., line 15)
   - `helpers/UserHelper.java` (e.g., inside `createUserPayload()`)

2. **Open** the `.feature` file you want to debug

3. Follow the **Two-Step Method** below

### How It Works

1. Launches Karate with `javaDebugPort: 5006` (enables JDWP agent)
2. You attach VS Code Java debugger to the same JVM
3. Feature breakpoints → handled by Karate debug adapter
4. Java breakpoints → handled by Java debugger (JDWP)

### Two-Step Method

1. **Start Karate with Java debug enabled:**
   - Select "1. Karate: Start with Java Debug"
   - Press F5
   - Wait for output: `Java debug agent listening on port 5006`

2. **Attach Java debugger:**
   - Select "2. Java: Attach to Karate"
   - Press F5

### Example launch.json

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "karate",
            "request": "launch",
            "name": "1. Karate: Start with Java Debug",
            "feature": "${file}",
            "karateEnv": "dev",
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
            "feature": "${file}",
            "karateEnv": "dev"
        }
    ]
}
```

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
| `1. Karate: Start with Java Debug` | Debug .feature with Java debug agent on port 5006 |
| `2. Java: Attach to Karate` | Attach to running JVM for Java breakpoints |
| `Karate: Debug Feature Only` | Debug .feature files only (no Java debugging) |

## Notes

- **Feature breakpoints**: Handled by Karate Runner extension (DAP)
- **Java breakpoints**: Handled by VS Code Java debugger (JDWP)
- **JavaScript in Karate**: Runs in GraalJS; inspect via Karate variables panel
- **JSON files**: Data only; inspect when loaded into variables
