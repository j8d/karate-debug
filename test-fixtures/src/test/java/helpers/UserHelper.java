package helpers;

import java.util.HashMap;
import java.util.Map;

/**
 * Java helper class that can be called from Karate feature files.
 * Set breakpoints here to debug Java code during Karate test execution.
 */
public class UserHelper {

    /**
     * Creates a user payload with the given name and email.
     * Try setting a breakpoint on the first line inside this method.
     */
    public static Map<String, Object> createUserPayload(String name, String email) {
        Map<String, Object> user = new HashMap<>();
        user.put("name", name);           // Breakpoint here
        user.put("email", email);
        user.put("active", true);
        user.put("createdAt", System.currentTimeMillis());
        return user;
    }

    /**
     * Validates that an email address is in the correct format.
     */
    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        boolean isValid = email.contains("@") && email.contains(".");
        return isValid;  // Breakpoint here
    }

    /**
     * Generates a unique ID for testing.
     */
    public static String generateId() {
        String id = "USER-" + System.currentTimeMillis();
        return id;
    }
}

