@ignore
@syntax-test @demo
Feature: Karate Syntax Highlighting Test
    # This is a comment - for testing syntax highlighting only
    # Covers: keywords, expressions, assertions, JSON, JavaScript, Java interop

Background:
    * def baseUrl = 'https://api.example.com'
    * url baseUrl
    * configure headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' }
    * configure retry = { count: 3, interval: 1000 }
    * print java.time.Instant.now().toString()
    * def uuid = java.util.UUID.randomUUID().toString()

@get-request
Scenario: GET Request with Query Parameters
    * path 'users', userId
    * param status = 'active'
    * params { limit: 10, offset: 0 }
    * header Authorization = 'Bearer ' + authToken
    * header X-Request-Id = uuid
    * method GET
    * status 200
    * match response.id == userId
    * match response.name == '#string'
    * match response.createdAt == '#notnull'
    * match response.tags == '#[] #string'
    * match response.metadata == '#object'
    * print 'Response:', response

@post-request
Scenario: POST Request with JSON Body
    * path 'users'
    * request requestBody
    * method POST
    * status 201
    * match response == { id: '#uuid', name: 'John Doe', email: '#string', roles: '#array', settings: '#object' }
    * match response.roles contains 'admin'
    * match response.roles !contains 'guest'
    * match response.settings.notifications == true
    * def createdId = response.id

@assertions
Scenario: Various Match Assertions
    * match data.count == 5
    * match data.count != 10
    * match data.count > 0
    * match data.count < 10
    * match data.count >= 5
    * match data.count <= 5
    * match data.items == [1, 2, 3]
    * match data.items contains 2
    * match data.items contains any [2, 99]
    * match data.items contains only [1, 2, 3]
    * match data.items contains deep { }
    * match data == '#object'
    * match data.nested == { key: '#string' }
    * match each data.items == '#number'

@javascript
Scenario: JavaScript Expressions and Functions
    * def add = function(a, b) { return a + b }
    * def result = add(2, 3)
    * match result == 5
    * def greet =
    """
    function(name) {
      var greeting = 'Hello, ' + name + '!';
      return greeting.toUpperCase();
    }
    """
    * match greet('World') == 'HELLO, WORLD!'
    * def list = karate.filter([1, 2, 3, 4, 5], function(x) { return x > 2 })
    * match list == [3, 4, 5]
    * def mapped = karate.map([1, 2, 3], function(x) { return x * 2 })
    * match mapped == [2, 4, 6]

@java-interop
Scenario: Java Interop and Type Definitions
    * def StringUtils = Java.type('org.apache.commons.lang3.StringUtils')
    * def ArrayList = Java.type('java.util.ArrayList')
    * def list = new ArrayList()
    * eval list.add('item1')
    * def dateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    * def now = java.time.Instant.now()
    * def formatted = dateFormatter.withZone(java.time.ZoneOffset.UTC).format(now)

@conditionals
Scenario: Conditional Logic and Karate Functions
    * def value = 10
    * def status = value > 5 ? 'high' : 'low'
    * match status == 'high'
    * def optional = karate.get('missingVar', 'default')
    * match optional == 'default'
    * if (value > 5) karate.log('Value is high')
    * def env = karate.env
    * def config = karate.read('classpath:config.json')
    * def merged = karate.merge({ a: 1 }, { b: 2 })
    * match merged == { a: 1, b: 2 }

@call-feature
Scenario: Calling Other Features
    * def authResult = call read('classpath:helpers/auth.feature')
    * def token = authResult.token
    * def setupData = callonce read('classpath:helpers/setup.feature')
    * call read('classpath:helpers/cleanup.feature') { id: '#(createdId)' }

@data-table
Scenario Outline: Data-Driven Testing with Examples
    * path 'calculate'
    * request { operation: '<operation>', a: <a>, b: <b> }
    * method POST
    * status 200
    * match response.result == <expected>

    Examples:
      | operation | a  | b  | expected |
      | add       | 5  | 3  | 8        |
      | subtract  | 10 | 4  | 6        |
      | multiply  | 3  | 7  | 21       |

@retry-until
Scenario: Retry Until Condition Met
    * configure retry = { count: 5, interval: 2000 }
    * path 'status'
    * method GET
    * retry until response.status == 'complete'
    * status 200

@xml-handling  
Scenario: XML Request and Response
    * path 'soap/endpoint'
    * request 
    """
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
      <soapenv:Body>
        <GetUser><Id>123</Id></GetUser>
      </soapenv:Body>
    </soapenv:Envelope>
    """
    * method POST
    * status 200
    * match /Envelope/Body/User/Name == 'John'
