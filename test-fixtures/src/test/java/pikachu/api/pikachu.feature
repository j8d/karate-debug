
Feature: Pokemon API

  Background:
    * url 'https://pokeapi.co/api/v2'

  Scenario: Get pokemon by name
    Given path '/pokemon/pikachu'
    When method GET
    * print 'after GET'
    Then status 200
    And match response.name == 'pikachu'
    And match response.id == 25
    And match response.types == '#array'

  Scenario: Get pokemon abilities
    Given path '/pokemon/charizard'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.abilities == '#array'
