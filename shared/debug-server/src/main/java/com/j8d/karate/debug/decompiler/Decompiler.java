package com.j8d.karate.debug.decompiler;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decompiles Java bytecode on-the-fly using the CFR decompiler.
 * Used when stepping into framework/library code that doesn't have source files on disk.
 */
public class Decompiler {
    private static final Logger log = LoggerFactory.getLogger(Decompiler.class);

    /**
     * Decompiles bytecode to Java source code.
     *
     * @param bytecode  The class file bytecode
     * @param className The fully qualified class name (e.g., "com.intuit.karate.core.ScenarioIterator")
     * @return The decompiled Java source code, or null if decompilation fails
     */
    public String decompile(byte[] bytecode, String className) {
        if (bytecode == null || bytecode.length == 0) {
            log.warn("Cannot decompile: bytecode is null or empty for {}", className);
            return null;
        }

        try {
            StringBuilder result = new StringBuilder();

            // Create a ClassFileSource that provides our bytecode
            ClassFileSource source = new ByteArrayClassFileSource(bytecode, className);

            // Create an OutputSinkFactory that captures the decompiled output
            OutputSinkFactory output = new StringOutputSinkFactory(result);

            // Build and run the decompiler
            CfrDriver driver = new CfrDriver.Builder()
                    .withClassFileSource(source)
                    .withOutputSink(output)
                    .withOptions(Map.of(
                            "showversion", "false",
                            "comments", "false",
                            "decodestringswitch", "true",
                            "sugarboxing", "true",
                            "decodeenumswitch", "true",
                            "removebadgenerics", "true"
                    ))
                    .build();

            // Convert class name to path format (com.Foo -> com/Foo.class)
            String classPath = className.replace('.', '/') + ".class";
            driver.analyse(List.of(classPath));

            String decompiledSource = result.toString();
            if (decompiledSource.isEmpty()) {
                log.warn("Decompilation produced empty result for {}", className);
                return null;
            }

            log.debug("Successfully decompiled {} ({} bytes -> {} chars)", 
                    className, bytecode.length, decompiledSource.length());
            return decompiledSource;

        } catch (Exception e) {
            log.error("Failed to decompile {}: {}", className, e.getMessage(), e);
            return null;
        }
    }

    /**
     * ClassFileSource implementation that serves bytecode from a byte array.
     */
    private static class ByteArrayClassFileSource implements ClassFileSource {
        private final byte[] bytecode;
        private final String className;

        ByteArrayClassFileSource(byte[] bytecode, String className) {
            this.bytecode = bytecode;
            this.className = className;
        }

        @Override
        public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
            // Not needed for single-class decompilation
        }

        @Override
        public Collection<String> addJar(String jarPath) {
            return Collections.emptyList();
        }

        @Override
        public String getPossiblyRenamedPath(String path) {
            return path;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String path) throws IOException {
            // CFR asks for the class content
            String expectedPath = className.replace('.', '/') + ".class";
            if (path.equals(expectedPath) || path.endsWith(expectedPath)) {
                return Pair.make(bytecode, path);
            }
            // For any other class (inner classes, etc.), we don't have the bytecode
            throw new IOException("Class not available: " + path);
        }
    }

    /**
     * OutputSinkFactory that captures decompiled Java source to a StringBuilder.
     */
    private static class StringOutputSinkFactory implements OutputSinkFactory {
        private final StringBuilder result;

        StringOutputSinkFactory(StringBuilder result) {
            this.result = result;
        }

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            // Support STRING for all sink types (JAVA, EXCEPTION, PROGRESS, SUMMARY)
            return List.of(SinkClass.STRING);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkType == SinkType.JAVA && sinkClass == SinkClass.STRING) {
                // Capture decompiled Java source
                return (Sink<T>) (Sink<String>) result::append;
            }
            // For other sink types (EXCEPTION, PROGRESS, SUMMARY), return a no-op sink
            // CFR will call write() on these, so we can't return null
            return (Sink<T>) (Sink<String>) s -> { /* no-op */ };
        }
    }
}

