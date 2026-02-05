package polyglot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java helper class for polyglot debugging test.
 * Set breakpoints here to test Java debugging.
 */
public class PolyglotHelper {

    /**
     * Validate an order and return validation result.
     */
    public static Map<String, Object> validateOrder(Map<String, Object> order) {
        // BREAKPOINT HERE - test Java debugging (line 18)
        Map<String, Object> result = new HashMap<>();
        
        // Check if order has required fields
        boolean hasId = order.containsKey("id");
        boolean hasItems = order.containsKey("items");
        
        result.put("valid", hasId && hasItems);
        result.put("hasId", hasId);
        result.put("hasItems", hasItems);
        
        if (hasItems) {
            List<?> items = (List<?>) order.get("items");
            result.put("itemCount", items.size());
        } else {
            result.put("itemCount", 0);
        }
        
        return result;
    }

    /**
     * Calculate tax for an amount.
     * BREAKPOINT HERE - test Java debugging
     */
    public static double calculateTax(double amount, double taxRate) {
        double tax = amount * taxRate;
        double total = amount + tax;
        return Math.round(total * 100.0) / 100.0;
    }

    /**
     * Generate an order confirmation.
     * BREAKPOINT HERE - test Java debugging
     */
    public static Map<String, Object> generateConfirmation(String orderId, double total) {
        Map<String, Object> confirmation = new HashMap<>();
        
        confirmation.put("orderId", orderId);
        confirmation.put("total", total);
        confirmation.put("confirmationNumber", "CONF-" + System.currentTimeMillis());
        confirmation.put("status", "confirmed");
        
        return confirmation;
    }
}

