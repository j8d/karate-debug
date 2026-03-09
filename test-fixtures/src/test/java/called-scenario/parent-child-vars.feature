Feature: Test parent-child variable scoping and Match Diagnostics timing

  This test reproduces two bugs that were fixed:

  1. Variable scoping bug: Called scenarios (@ignore) could not access parent scope variables
     or magic variables (like 'karate', 'response') because they were stored in GraalVM JS
     bindings but not in engine.vars map.

  2. Match Diagnostics timing bug (VS Code only): When stepping through code with a breakpoint,
     the Match Diagnostics Provider would evaluate ALL match statements in the current scenario,
     including those that reference variables not yet defined. This caused ReferenceError and
     corrupted the Karate engine state.

  To test bug #2: Set breakpoint at line 33 (def fileName) and step over it. Before the fix,
  Match Diagnostics would try to evaluate lines 42 and 49 (which reference fileName and
  resultMessage) and fail with "not defined" error, causing the step to fail.

Background:
  * def parentVar = 'I am from parent'
  * def base64CCD = 'SGVsbG8gV29ybGQ='

Scenario: Parent scenario that calls child
  # Define variables in parent
  * def psfile = base64CCD
  
  # Call child scenario (like @uploadccdtos3)
  * call read('@childScenario')
  
  # Verify child executed successfully
  * match childResult == 'SUCCESS'

@childScenario
@ignore
Scenario: Child scenario that defines new variables
  # This mimics the @uploadccdtos3 scenario
  # It should be able to access parent variables AND define new ones

  # Access parent variable directly (without karate.get)
  * def base64String = base64CCD
  * match base64String == 'SGVsbG8gV29ybGQ='

  # Define a new variable (like fileName) - SET BREAKPOINT HERE TO TEST STEPPING BUG
  * def fileName = 'encoded-20230525184521'

  # Define another variable
  * def bucketName = 'test-bucket'

  # Define result message early
  * def resultMessage = 'upload success'

  # These match statements reference variables defined above
  # When paused at line 43 (def fileName), Match Diagnostics would try to evaluate
  # these lines and fail with "fileName is not defined" or "resultMessage is not defined"
  # before our fix (because it evaluated ALL match statements in the scenario, not just
  # those at or before the current line)
  * match fileName == 'encoded-20230525184521'
  * match resultMessage contains 'success'

  # Set result for parent to verify
  * def childResult = 'SUCCESS'

