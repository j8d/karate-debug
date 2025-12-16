/**
 * JavaScript utilities that can be loaded in Karate feature files.
 * Set breakpoints here to debug JavaScript during Karate test execution.
 * 
 * Usage in feature file:
 *   * def utils = read('classpath:helpers/utils.js')
 *   * def result = utils.formatName('john', 'doe')
 */

function() {
    var module = {};

    /**
     * Formats a name from first and last name.
     * Try setting a breakpoint on the line with fullName.
     */
    module.formatName = function(firstName, lastName) {
        var first = firstName.charAt(0).toUpperCase() + firstName.slice(1);
        var last = lastName.charAt(0).toUpperCase() + lastName.slice(1);
        var fullName = first + ' ' + last;  // Breakpoint here
        return fullName;
    };

    /**
     * Calculates the total price with tax.
     */
    module.calculateTotal = function(price, taxRate) {
        var tax = price * taxRate;
        var total = price + tax;  // Breakpoint here
        return Math.round(total * 100) / 100;
    };

    /**
     * Validates a user object has required fields.
     */
    module.validateUser = function(user) {
        var errors = [];
        if (!user.name) errors.push('name is required');
        if (!user.email) errors.push('email is required');
        var isValid = errors.length === 0;  // Breakpoint here
        return { valid: isValid, errors: errors };
    };

    return module;
}

