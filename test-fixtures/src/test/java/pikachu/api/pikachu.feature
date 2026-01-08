
Feature: Pokemon API

  Background:
    * url 'https://pokeapi.co/api/v2'

  Scenario: Get pokemon by name
    Given path '/pokemon/pikachu'
    * print 'after path'
    When method GET
    * print 'after GET'
    Then status 200
    * print 'after status 200'
    And match response.name == 'pikachu'
    * print 'after match name'
    And match response.id == 25
    * print 'after match id'
    And match response.types == '#array'
    * karate.log('after match types')

  Scenario: Get pokemon abilities
    Given path '/pokemon/charizard'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.abilities == '#array'
