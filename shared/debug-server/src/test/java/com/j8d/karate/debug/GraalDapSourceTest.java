package com.j8d.karate.debug;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test to verify that GraalVM DAP source content matches original .js files.
 * This validates the source content matching approach for JavaScript debugging.
 *
 * DISABLED: GraalVM version mismatch between Karate (polyglot 24.0.0) and
 * dap-tool/chromeinspector-tool (truffle 25.0.2). These are exploratory tests
 * that verify GraalVM internals, not our code.
 */
@Disabled("GraalVM version mismatch between Karate (24.0.0) and dap-tool (25.0.2)")
public class GraalDapSourceTest {

    @Test
    void compareSourceApproaches() throws Exception {
        Engine engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        Context context = Context.newBuilder("js")
            .engine(engine)
            .allowAllAccess(true)
            .build();

        // The JavaScript content (as would be read from a file)
        String jsFileContent = """
            (function() {
                function add(a, b) {
                    return a + b;
                }
                return { add: add };
            })()
            """;

        // Approach 1: What Karate does - inline eval
        Value result1 = context.eval("js", jsFileContent);
        System.out.println("Inline eval result: " + result1);
        System.out.println("Has source location: " + result1.getSourceLocation());

        // Approach 2: Using Source.newBuilder with a file name
        Source namedSource = Source.newBuilder("js", jsFileContent, "test-helper.js")
            .uri(URI.create("file:///test/test-helper.js"))
            .build();
        Value result2 = context.eval(namedSource);
        System.out.println("\nNamed source eval result: " + result2);
        System.out.println("Has source location: " + result2.getSourceLocation());

        // Both should return the same functionality
        Value addFn1 = result1.getMember("add");
        Value addFn2 = result2.getMember("add");

        assertEquals(5, addFn1.execute(2, 3).asInt());
        assertEquals(5, addFn2.execute(2, 3).asInt());

        // The key difference: source location
        if (addFn2.getSourceLocation() != null) {
            System.out.println("\nNamed source location: " +
                addFn2.getSourceLocation().getSource().getName());
        }

        context.close();
        engine.close();
    }

    /**
     * Test that verifies GraalVM DAP source content matches the original file.
     * This uses a simple approach: evaluate JS and check what GraalVM stores.
     */
    @Test
    void verifySourceContentPreserved() throws Exception {
        // Read the actual JS file content
        Path jsFilePath = Path.of("../../test-fixtures/src/test/java/polyglot/polyglot-helper.js");
        if (!Files.exists(jsFilePath)) {
            System.out.println("JS file not found at: " + jsFilePath.toAbsolutePath());
            System.out.println("Skipping test - run from correct directory");
            return;
        }

        String originalFileContent = Files.readString(jsFilePath);
        System.out.println("=== Original file content ===");
        System.out.println("Length: " + originalFileContent.length() + " chars");
        System.out.println("First 300 chars:");
        System.out.println(originalFileContent.substring(0, Math.min(300, originalFileContent.length())));
        System.out.println("===");

        // Create GraalVM context WITHOUT DAP (simpler test)
        Engine engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        Context context = Context.newBuilder("js")
            .engine(engine)
            .allowAllAccess(true)
            .build();

        // Evaluate the JS file content (simulating what Karate does)
        System.out.println("\nEvaluating JS content (like Karate does)...");
        Value result = context.eval("js", originalFileContent);
        System.out.println("Eval result: " + result);

        // Check if we can get source from the result
        var sourceLocation = result.getSourceLocation();
        System.out.println("Source location: " + sourceLocation);

        if (sourceLocation != null) {
            var source = sourceLocation.getSource();
            System.out.println("Source name: " + source.getName());
            System.out.println("Source path: " + source.getPath());
            System.out.println("Source URI: " + source.getURI());

            // Get the source content that GraalVM stored
            String storedContent = source.getCharacters().toString();
            System.out.println("\n=== Stored source content ===");
            System.out.println("Length: " + storedContent.length() + " chars");
            System.out.println("First 300 chars:");
            System.out.println(storedContent.substring(0, Math.min(300, storedContent.length())));
            System.out.println("===");

            // Compare!
            boolean contentMatches = originalFileContent.equals(storedContent);
            System.out.println("\n*** CONTENT MATCHES: " + contentMatches + " ***");

            if (!contentMatches) {
                System.out.println("Difference analysis:");
                System.out.println("Original length: " + originalFileContent.length());
                System.out.println("Stored length: " + storedContent.length());
            }
        } else {
            System.out.println("No source location available from result Value");
            System.out.println("This is expected for inline eval - source info is internal to GraalVM");
        }

        // Test with a function from the result
        Value processOrder = result.getMember("processOrder");
        if (processOrder != null && processOrder.canExecute()) {
            var fnSourceLocation = processOrder.getSourceLocation();
            System.out.println("\nFunction processOrder source location: " + fnSourceLocation);
            if (fnSourceLocation != null) {
                var fnSource = fnSourceLocation.getSource();
                System.out.println("Function source name: " + fnSource.getName());
                String fnContent = fnSource.getCharacters().toString();
                System.out.println("Function source content length: " + fnContent.length());

                // This should be the FULL file content, not just the function
                boolean fnContentMatches = originalFileContent.equals(fnContent);
                System.out.println("*** FUNCTION SOURCE MATCHES ORIGINAL: " + fnContentMatches + " ***");
            }
        }

        context.close();
        engine.close();
    }
}

