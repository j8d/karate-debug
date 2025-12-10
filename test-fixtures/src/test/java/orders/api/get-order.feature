Feature: Get Order API

  Background:
    * url 'https://api.example.com'

  Scenario: Get order by ID
    Given path '/orders/ORD-001'
    When method GET
    Then status 200
    And match response.orderId == 'ORD-001'
    And match response.items == '#array'

  Scenario: Get order with items
    Given path '/orders/ORD-002'
    When method GET
    Then status 200
    And match response.items[0].productId == '#string'
    And match response.items[0].quantity == '#number'

