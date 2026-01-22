Feature: Pokemon API

  Scenario: Get pokemon by name
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/pikachu'
    When method GET
    Then status 200
    And match response.name == 'pikachu'
    And match response.id == 25
    And print 'ted'
    And print 'thymeleafs'
    And print 'thymeleaf'
    And print 'asdf'
    And print 'leaf'
    And match response.types == '#array'
    And call read('classpath:pikachu/api/pikachu-abilities.feature')

  Scenario: Get pokemon abilities
    Given url 'https://pokeapi.co/api/v2'
    And path '/pokemon/charizard'
    When method GET
    Then status 200
    And match response.name == 'charizard'
    And match response.abilities == '#array'
