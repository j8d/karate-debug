package helpers;

import com.intuit.karate.junit5.Karate;

/**
 * JUnit 5 test runner for Karate tests.
 *
 * Run with Maven debug mode to attach Java debugger:
 *   mvn test -Dtest=helpers.DebugRunner -Dmaven.surefire.debug
 *
 * Then attach VS Code Java debugger on port 5005.
 */
class DebugRunner {

    @Karate.Test
    Karate testAll() {
        return Karate.run("debug-all-types").relativeTo(getClass());
    }

    @Karate.Test
    Karate testUsers() {
        return Karate.run("classpath:users").relativeTo(getClass());
    }

    @Karate.Test
    Karate testOrders() {
        return Karate.run("classpath:orders").relativeTo(getClass());
    }
}
