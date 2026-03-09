Feature: Test parent-child variable scoping and Match Diagnostics timing

  This test reproduces two bugs that were fixed:

  1. Variable scoping bug: Called scenarios (@ignore) could not access parent scope variables
     or magic variables (like 'karate', 'response') because they were stored in GraalVM JS
     bindings but not in engine.vars map.

  2. Match Diagnostics evaluation bug (VS Code only): When stepping through code with a breakpoint,
     the Match Diagnostics Provider would evaluate match statements that reference variables not yet
     defined. Without proper server-side protection, this caused ReferenceError and corrupted the
     Karate engine state.

  To test bug #2: Set breakpoint at line 43 (def fileName) and step over it. Before the fix,
  Match Diagnostics would evaluate lines 56-57 (which reference fileName and resultMessage) and
  the server would throw ReferenceError, corrupting the engine. After the fix, the server safely
  checks variable existence and returns "Variable not defined" error without corrupting the engine.

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
  # When paused at line 43 (def fileName), Match Diagnostics evaluates these lines
  # Before the fix: Server would throw ReferenceError for undefined variables, corrupting engine
  # After the fix: Server safely checks hasVariable() and returns "Variable not defined" error
  # This allows Match Diagnostics to show "look ahead" failures without breaking execution
  * match fileName == 'encoded-20230525184521'
  * match resultMessage contains 'success'

  # Set result for parent to verify
  * def childResult = 'SUCCESS'

