Feature: Debug All File Types Demo
  This feature demonstrates debugging across .feature, .java, .js, and .json files.
  Set breakpoints in each file type and step through the execution.

  Background:
    # Load JavaScript utilities (call the function to get the module)
    * def utils = call read('classpath:helpers/utils.js')
    # Load JSON test data
    * def testData = read('classpath:helpers/test-data.json')
    # Configure Java interop
    * def UserHelper = Java.type('helpers.UserHelper')

  Scenario: Debug with Java helper
    # Set a breakpoint on the next line and step into the Java method
    * def userId = UserHelper.generateId()
    * print 'Generated ID:', userId

    # This calls the Java createUserPayload method - set breakpoint there
    * def userPayload = UserHelper.createUserPayload('John', 'john@test.com')
    * print 'User payload:', userPayload

    # Validate email using Java
    * def isValid = UserHelper.isValidEmail('john@test.com')
    * match isValid == true

  Scenario: Debug with JavaScript utilities
    # Set a breakpoint in utils.js formatName function
    * def formattedName = utils.formatName('john', 'doe')
    * match formattedName == 'John Doe'

    # Set a breakpoint in utils.js calculateTotal function
    * def total = utils.calculateTotal(100, 0.08)
    * match total == 108.0

    # Set a breakpoint in utils.js validateUser function
    * def validation = utils.validateUser({ name: 'Test', email: 'test@example.com' })
    * match validation.valid == true

  Scenario: Debug with JSON data
    # Access nested JSON data - set breakpoint here to inspect testData
    * def config = testData.config
    * match config.baseUrl == 'https://api.example.com'

    # Iterate through users from JSON
    * def users = testData.users
    * match users[0].name == 'John Doe'

    # Use test case data from JSON
    * def validUser = testData.testCases.validUser
    * def result = utils.validateUser(validUser)
    * match result.valid == true

  Scenario: Combined debugging flow
    # Step 1: Load data from JSON
    * def userData = testData.testCases.validUser

    # Step 2: Format name using JavaScript
    * def nameParts = userData.name.split(' ')
    * def formatted = utils.formatName(nameParts[0], nameParts[1])

    # Step 3: Create payload using Java
    * def payload = UserHelper.createUserPayload(formatted, userData.email)

    # Step 4: Validate using JavaScript
    * def validation = utils.validateUser(payload)
    * match validation.valid == true
    * print 'Final payload:', payload