# Variable Scoping Fix - Summary

## Problem

The Karate debug plugin had a critical bug where **parent scenario variables and config variables were invisible** when debugging called scenarios (scenarios with `@ignore` that are invoked via `call read('@scenarioName')`).

### Symptoms

1. Variables from parent scenarios were not visible in the Variables view
2. Config variables (from `karate-config.js`) were not visible
3. Evaluating expressions that referenced these variables failed with `ReferenceError`
4. Users had to use workarounds like `karate.get('variableName')` to access these variables

### Example

```karate
Scenario: Parent
  * def myVar = 'hello'
  * call read('@child')

@child
@ignore
Scenario: Child
  # This would fail in the debug plugin:
  * def result = myVar  # ReferenceError: myVar is not defined
  
  # Workaround that was needed:
  * def myVarCopy = karate.get('myVar')  # This worked
```

## Root Cause

Karate uses **magic variables** to pass parent and config variables to called scenarios. These are stored in the JavaScript engine bindings (`JS.bindings`) but NOT in `engine.vars`.

The debug plugin's `getVariables()` method only looked at `engine.vars`, so it missed all the magic variables.

From Karate's source code comment:
```java
// magic variables are only in the JS engine - [ see ScenarioEngine.init() ]
// and not "visible" and tracked in ScenarioEngine.vars
// one consequence is that they won't show up in the debug variables view
```

## The Fix

### Changed File
`shared/debug-server/src/main/java/com/j8d/karate/debug/KarateDebugger.java`

### What Changed

**Before:**
```java
public JsonArray getVariables(int variablesReference) {
    JsonArray variables = new JsonArray();
    if (currentRuntime != null) {
        Map<String, Variable> vars = currentRuntime.engine.vars;  // Only local vars
        
        for (Map.Entry<String, Variable> entry : vars.entrySet()) {
            // ... add to variables array
        }
    }
    return variables;
}
```

**After:**
```java
public JsonArray getVariables(int variablesReference) {
    JsonArray variables = new JsonArray();
    if (currentRuntime != null) {
        JsEngine jsEngine = currentRuntime.engine.getJsEngine();  // Get JS engine
        if (jsEngine != null) {
            Set<String> allKeys = jsEngine.bindings.getMemberKeys();  // All variables
            
            for (String key : allKeys) {
                // Skip internal variables and functions
                if (key.startsWith("_") || key.equals("karate")) {
                    continue;
                }
                
                // Get value from JS bindings (includes magic variables)
                Object value = jsEngine.bindings.getMember(key);
                
                // Skip functions
                if (value instanceof Value) {
                    Value graalValue = (Value) value;
                    if (graalValue.canExecute()) {
                        continue;
                    }
                    value = JsValue.toJava(graalValue);
                }
                
                // ... add to variables array
            }
        }
    }
    return variables;
}
```

### Key Changes

1. **Access JS engine bindings** instead of just `engine.vars`
2. **Filter out internal variables** (those starting with `_`) and the `karate` object
3. **Skip functions** to only show data variables
4. **Convert GraalVM Values** to Java objects for display
5. **Fixed evaluate() and evaluateMatch()** to use `engine.hasVariable()` instead of `engine.vars.containsKey()` - this allows hover/watch/debug console to access magic variables
6. **Fixed Match Diagnostics evaluation** - Added hasVariable() check in evaluateMatch() to safely handle undefined variables by returning an error message instead of throwing ReferenceError

## Testing

### Test Case
Created `test-fixtures/src/test/java/called-scenario/parent-child-vars.feature` to verify both bugs:

**Bug #1 - Variable Scoping:**
- Parent scenario variables are accessible in called scenarios
- Config variables are accessible in called scenarios
- Magic variables (karate, response, etc.) are accessible
- Variables appear in the debug Variables view

**Bug #2 - Match Diagnostics Evaluation (VS Code only):**
- When paused at line 43 (`def fileName`), Match Diagnostics evaluates ALL match statements in the scenario (including lines 56-57)
- Before fix: Server would throw ReferenceError when evaluating undefined variables, corrupting the Karate engine state
- After fix: Server safely checks hasVariable() and returns "Variable not defined" error message
- This preserves the valuable "look ahead" functionality while preventing engine corruption

### Verification Steps

**To test bug #1 (Variable Scoping):**
1. Set a breakpoint in the `@childScenario` scenario
2. Run the test in debug mode
3. Check the Variables view - you should now see:
   - `parentVar` = "I am from parent"
   - `base64CCD` = "SGVsbG8gV29ybGQ="
   - `karate` = (magic variable)
   - All other parent scope and config variables

**To test bug #2 (Match Diagnostics) in VS Code:**
1. Set breakpoint at line 43 (`* def fileName = 'encoded-20230525184521'`)
2. Run in debug mode
3. Step over (F10) the line
4. Before the fix: Would fail with ReferenceError, corrupting the engine
5. After the fix: Steps successfully, Match Diagnostics shows "Variable not defined" error for lines 56-57 without breaking execution
6. Bonus: You can now see "look ahead" failures - match statements that will fail when execution reaches them

## Impact

### What Works Now

✅ Parent scenario variables are visible in called scenarios  
✅ Config variables are visible in called scenarios  
✅ Variable evaluation works without `karate.get()` workarounds  
✅ Debug Variables view shows all accessible variables  
✅ Matches native Karate runner behavior  

### Migration

If you have existing tests with `karate.get()` workarounds, you can now remove them:

```karate
# Old workaround (still works, but no longer needed):
* def myVarCopy = karate.get('myVar')

# New direct access (now works in debug plugin):
* def result = myVar
```

## Build

The fix is included in `karate-debug-server-1.0.0.jar` and has been copied to both VS Code and IntelliJ extensions.

To rebuild:
```bash
cd shared/debug-server && mvn clean package -q
cp target/karate-debug-server-1.0.0.jar ../../vscode/resources/
cp target/karate-debug-server-1.0.0.jar ../../intellij/src/main/resources/
```

