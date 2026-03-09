Feature: Test variable scoping in called scenarios

Background:
  * def parentVar = 'I am from parent'
  * def parentNumber = 42

Scenario: Main scenario that calls another scenario
  # Define variables in parent scenario
  * def myData = { name: 'John', age: 30 }
  * def myList = [1, 2, 3]
  
  # Call the child scenario
  * call read('@childScenario')
  
  # Verify the child scenario could access parent variables
  * match childResult == 'SUCCESS'

@childScenario
@ignore
Scenario: Child scenario that accesses parent variables
  # This scenario should be able to access:
  # 1. Parent scenario variables (myData, myList, parentVar, parentNumber)
  # 2. Config variables (env, baseUrl)
  
  # Test access to parent variables
  * match parentVar == 'I am from parent'
  * match parentNumber == 42
  * match myData.name == 'John'
  * match myList[0] == 1
  
  # Test access to config variables
  * match env == 'dev'
  * match baseUrl == 'https://pokeapi.co/api/v2'
  
  # Set a result variable that parent can check
  * def childResult = 'SUCCESS'

