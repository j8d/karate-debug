
Feature: Pokemon API

  Background:
    * url 'https://pokeapi.co/api/v2'

  Scenario: Get pokemon by name
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/pikachu'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.id == 67
    And match response.types == '#string'

  Scenario: Get pokemon abilities
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/charizard'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.abilities == '#array'
