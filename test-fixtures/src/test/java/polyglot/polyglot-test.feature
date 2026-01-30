Feature: Polyglot Debugging Test
  Test file for debugging across Karate, JavaScript, and Java

  Background:
    # Load JavaScript helper
    * def jsHelper = read('classpath:polyglot/polyglot-helper.js')
    # Load Java helper
    * def JavaHelper = Java.type('polyglot.PolyglotHelper')

  Scenario: Process an order using all three languages
    # BREAKPOINT HERE - test Karate debugging
    * def order = { id: 'ORD-001', items: [{ name: 'Widget', price: 10.00, quantity: 2 }, { name: 'Gadget', price: 25.50, quantity: 1 }] }
    
    # Call Java to validate the order
    # BREAKPOINT HERE - before Java call
    * print 'Before JavaHelper.validateOrder()'
    * def validation = JavaHelper.validateOrder(order)
    * print 'After JavaHelper.validateOrder()'
    * match validation.valid == true
    * match validation.itemCount == 2
    
    # Call JavaScript to process the order
    # BREAKPOINT HERE - before JavaScript call
    * print 'Before jsHelper.processOrder()'
    * def processed = jsHelper.processOrder(order)
    * print 'After jsHelper.processOrder()'
    * match processed.orderId == 'ORD-001'
    * match processed.itemCount == 2
    * match processed.total == 45.50
    
    # Call Java to calculate tax
    # BREAKPOINT HERE - before tax calculation
    * print 'Before JavaHelper.calculateTax()'
    * def totalWithTax = JavaHelper.calculateTax(processed.total, 0.08)
    * print 'After JavaHelper.calculateTax()'
    * print 'Total with tax:', totalWithTax
    
    # Call Java to generate confirmation
    # BREAKPOINT HERE - before confirmation
    * def confirmation = JavaHelper.generateConfirmation(order.id, totalWithTax)
    * match confirmation.status == 'confirmed'
    * print 'Order confirmed:', confirmation

  Scenario: Test JavaScript string formatting
    # BREAKPOINT HERE - test Karate debugging
    * def amount = 123.456
    
    # Call JavaScript to format currency
    * def formatted = jsHelper.formatCurrency(amount)
    * match formatted == '$123.46'
    * print 'Formatted amount:', formatted

  Scenario: Test Java validation with missing fields
    # BREAKPOINT HERE - test Karate debugging
    * def invalidOrder = { name: 'Test Order' }
    
    # Call Java to validate - should fail validation
    * def validation = JavaHelper.validateOrder(invalidOrder)
    * match validation.valid == false
    * match validation.hasId == false
    * match validation.hasItems == false
    * print 'Validation result:', validation

