Feature: Pokemon API

  Scenario: Get pokemon by name
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/pikachu'
    When method GET
    Then status 200
    And match response.name == 'pikachu'
    And match response.id == 25
    And match response.types == '#array'

  Scenario: Get pokemon abilities
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/charizard'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.abilities == '#array'
