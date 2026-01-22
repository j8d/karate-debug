@abilities
Feature: Pokemon Abilities

  Scenario: Get pokemon abilities
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/pikachu'
    When method GET
    Then status 200
    And match response.abilities == '#array'
    And print 'Pikachu has abilities:', response.abilities

