Feature: Create User API

  Background:
    * url 'https://api.example.com'
    * header Content-Type = 'application/json'

  Scenario: Create a new user
    Given path '/users'
    And request { name: 'John Doe', email: 'john@example.com' }
    When method POST
    Then status 201
    And match response.id == '#number'
    And match response.name == 'John Doe'

  Scenario: Create user with invalid email
    Given path '/users'
    And request { name: 'Jane Doe', email: 'invalid-email' }
    When method POST
    Then status 400
    And match response.error contains 'email'

