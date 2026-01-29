/**
 * JavaScript helper for polyglot debugging test.
 * Set breakpoints here to test JavaScript debugging.
 */
(function() {

    function calculateTotal(items) {
        // BREAKPOINT HERE - test JS debugging
        var total = 0;

        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var subtotal = item.price * item.quantity;
            total = total + subtotal;
        }

        return total;
    }

    function formatCurrency(amount) {
        // BREAKPOINT HERE - test JS debugging
        return '$' + amount.toFixed(2);
    }

    function processOrder(order) {
        // BREAKPOINT HERE - test JS debugging
        var items = order.items;
        var total = calculateTotal(items);
        var formatted = formatCurrency(total);

        return {
            orderId: order.id,
            itemCount: items.length,
            total: total,
            formattedTotal: formatted,
            status: 'processed'
        };
    }

    return {
        calculateTotal: calculateTotal,
        formatCurrency: formatCurrency,
        processOrder: processOrder
    };
})()

