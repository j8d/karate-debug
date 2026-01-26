Feature: JavaScript Test

Scenario: Test with JS
  * print 'Starting test...'
  * def result = karate.eval("1 + 2")
  * print result
  * print 'Result:', result
  * match result == 3
