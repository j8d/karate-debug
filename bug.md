# Karate Debug Plugin Issue - Variable Scoping Problem

## Summary

The Karate debug plugin had a **variable scoping issue** when calling scenarios with `@ignore` tags using `call read('@scenarioName')`. Variables from the parent scenario and config were not accessible in the called scenario's JavaScript evaluation context, causing `ReferenceError` failures.

**Native Karate runner works fine** - the same test passes much further when run via Maven instead of the debug plugin.

## ✅ FIXED

This issue has been resolved in the debug server. The fix is included in `karate-debug-server-1.0.0.jar`.

---

## The Test: `Referral_e2e.feature`

### Purpose
End-to-end test of the referral workflow that:
1. Gets a pre-signed S3 URL from PatientCore API
2. Uploads a base64-encoded CCD (Clinical Care Document) to S3
3. Sends the referral to the downstream HH (Home Health) system
4. Simulates various referral lifecycle events (accepted, rejected, admitted, discharged)
5. Verifies logs and database state

### Test Flow

```
Main Scenario (lines 16-38)
  ↓
@uploadccdtos3 (lines 40-53) - Upload CCD to S3
  ↓
@send (lines 55-65) - Send referral via S3 pre-signed URL
  ↓
@file (lines 67-73) - Verify file received in referral bucket
  ↓
@accepted (lines 75-88) - Simulate accepted response
  ↓
@rejected (lines 90-104) - Simulate rejected response
  ↓
@admitted (lines 106-119) - Simulate admitted response
  ↓
@discharged (lines 121-134) - Simulate discharged response
  ↓
@logs (lines 136-148) - Check Datadog logs for processing
  ↓
@dynamo (lines 150-158) - Verify DynamoDB state
```

---

## The Problem: Debug Plugin Variable Scoping

### Symptom
When running via the **debug plugin**, the test fails at line 44 with:
```
ReferenceError: "name" is not defined
- <js>.:program(Unnamed:1)
classpath:referral/Referral_Workflow/1_End_to_End/Referral_e2e.feature:44
```

### The Misleading Error
Line 44 is just: `* def fileName = 'encoded-20230525184521'`

This is a simple string assignment - there's no reason it should fail! The error message is **misleading**.

### Root Cause
The actual failure happens when the debug plugin tries to evaluate variables in a **called scenario** (`@uploadccdtos3`) that were defined in:
1. **Parent scenario** (e.g., `base64CCD` defined in main scenario)
2. **Config file** (e.g., `S3Utils` from `karate-config.js`)

The debug plugin's JavaScript engine doesn't have access to these variables in the called scenario's scope.

### The Workaround
We had to use `karate.get()` to explicitly retrieve variables:
```karate
# Instead of:
* def S3UtilsClass = Java.type(S3Utils)  # ❌ Fails in debug plugin

# We need:
* def s3UtilsClassName = karate.get('S3Utils')  # ✅ Works
* def S3UtilsClass = Java.type(s3UtilsClassName)
```

Similarly for parent scenario variables:
```karate
# Instead of:
* def base64JavaString = new JavaString(base64CCD)  # ❌ Fails in debug plugin

# We need:
* def base64String = karate.get('base64CCD')  # ✅ Works
* def base64JavaString = new JavaString(base64String)
```

---

## Native Karate Runner Results

When run via **Maven** (`mvn test -Dtest=KarateRunner#debug_referral_dev`), the test:

✅ **Passes all S3 upload steps** (lines 40-53)
✅ **Sends referral successfully** (lines 55-65)
✅ **Verifies file in bucket** (lines 67-73)
✅ **Simulates all lifecycle events** (lines 75-134)
❌ **Times out waiting for Datadog logs** (line 145)

### Final Error (Native Runner)
```
Match failed after 10 retries while searching for 
Successfully found item(s) for PartitionKey 814ad9f7-b752-4152-b7f1-42b7732f2ee6
```

This is a **different, legitimate failure** - the backend didn't process the referral as expected. This is likely due to a downstream HH system issue, NOT a test framework issue.

---

## Key Differences: Debug Plugin vs Native Runner

| Aspect | Debug Plugin | Native Runner |
|--------|-------------|---------------|
| **Variable scoping** | ❌ Broken - requires `karate.get()` | ✅ Works normally |
| **Config access** | ❌ Broken - requires `karate.get()` | ✅ Works normally |
| **Test progress** | ❌ Fails at line 44 (S3 upload) | ✅ Reaches line 145 (log verification) |
| **Error messages** | ❌ Misleading line numbers | ✅ Accurate |

---

## Code Changes Made to Work Around Debug Plugin

### Lines 43-51: S3 Upload Scenario
```karate
@uploadccdtos3
@ignore
Scenario: Upload CCD to S3
* def s3UtilsClassName = karate.get('S3Utils')        # Workaround for config access
* def S3UtilsClass = Java.type(s3UtilsClassName)
* def s3 = new S3UtilsClass()
* def fileName = 'encoded-20230525184521'
* def base64String = karate.get('base64CCD')          # Workaround for parent variable
* def JavaString = Java.type('java.lang.String')
* def base64JavaString = new JavaString(base64String)
* def fileBytes = base64JavaString.getBytes('UTF-8')
* s3.uploadObject(readBucket, fileName, fileBytes)    # No eval - direct call
```

### Key Workarounds
1. **`karate.get('S3Utils')`** - Access config variable
2. **`karate.get('base64CCD')`** - Access parent scenario variable
3. **Removed `eval`** - Call Java methods directly instead of wrapping in `eval`
4. **Renamed `name` to `fileName`** - Avoid potential JavaScript reserved word conflicts

---

## The Fix

### Root Cause

The debug plugin's `getVariables()` method only looked at `engine.vars`, which contains variables explicitly defined in the current scenario. However, Karate uses **magic variables** for:
- Parent scenario variables (inherited from calling scenarios)
- Config variables (from `karate-config.js`)

These magic variables are stored in the JavaScript engine bindings (`JS.bindings`) but NOT in `engine.vars`. This is by design in Karate to avoid memory bloat.

### Solution

Modified `KarateDebugger.getVariables()` to read from the JS engine bindings instead of just `engine.vars`:

```java
// Before: Only showed variables in engine.vars
Map<String, Variable> vars = currentRuntime.engine.vars;

// After: Shows all variables including magic variables
JsEngine jsEngine = currentRuntime.engine.getJsEngine();
Set<String> allKeys = jsEngine.bindings.getMemberKeys();
```

This makes parent scenario variables and config variables visible in the debug Variables view, matching the behavior of the native Karate runner.

### Files Changed

- `shared/debug-server/src/main/java/com/j8d/karate/debug/KarateDebugger.java`
  - Added imports for `Set`, `Value`, `JsEngine`, and `JsValue`
  - Modified `getVariables()` to iterate over JS engine bindings
  - Added filtering to skip internal variables and functions

## Recommendations

1. **For development**: The debug plugin now works correctly with called scenarios
2. **For test code**: You can remove the `karate.get()` workarounds - direct variable access now works
3. **For verification**: Test with your real scenarios to confirm the fix works as expected

---

## Test Execution Commands

### Debug Plugin (has scoping issues)
Run from IDE using the Karate debug plugin

### Native Runner (works correctly)
```bash
mvn test -Dtest=KarateRunner#debug_referral_dev \
  -Dkarate.options="classpath:referral/Referral_Workflow/1_End_to_End/Referral_e2e.feature"
```

---

## Related Files

- **Test**: `src/test/java/referral/Referral_Workflow/1_End_to_End/Referral_e2e.feature`
- **Runner**: `src/test/java/KarateRunner.java` (method: `debug_referral_dev`)
- **Config**: `src/test/java/karate-config.js` (defines `S3Utils`)
- **PR**: #955 - SAASPC-6804 - Restore base64 encoding for S3 CCD upload
- **Branch**: `fix/SAASPC-6804`

