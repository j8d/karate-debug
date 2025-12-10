Feature: Get User API

  Background:
    * url 'https://api.example.com'
    * header Accept = 'application/json'

  Scenario: Get user by ID
    Given path '/users/123'
    When method GET
    Then status 200
    And match response.id == 123
    And match response.name == '#string'

  Scenario: Get user not found
    Given path '/users/999999'
    When method GET
    Then status 404
    And match response.error == 'User not found'

  Scenario Outline: Get users with different IDs
    Given path '/users/<id>'
    When method GET
    Then status <status>

    Examples:
      | id  | status |
      | 1   | 200    |
      | 2   | 200    |
      | 999 | 404    |

