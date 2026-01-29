package com.j8d.karate.debug.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for decompiling Java classes on-the-fly.
 * Coordinates bytecode loading and decompilation, with caching.
 */
public class DecompilationService {
    private static final Logger log = LoggerFactory.getLogger(DecompilationService.class);

    private final BytecodeLoader bytecodeLoader;
    private final Decompiler decompiler;
    
    // Cache of decompiled sources: className -> source code
    private final Map<String, String> sourceCache = new ConcurrentHashMap<>();

    /**
     * Creates a DecompilationService with the given classpath entries.
     *
     * @param classpathEntries List of JAR file paths or directory paths
     */
    public DecompilationService(List<String> classpathEntries) {
        this.bytecodeLoader = new BytecodeLoader(classpathEntries);
        this.decompiler = new Decompiler();
        log.info("DecompilationService initialized with {} classpath entries", classpathEntries.size());
    }

    /**
     * Gets the source for a class.
     * First tries to load from -sources.jar (original source with correct line numbers).
     * Falls back to decompilation if source JAR is not available.
     * Results are cached to avoid repeated lookups.
     *
     * @param className Fully qualified class name (e.g., "com.intuit.karate.core.ScenarioIterator")
     * @return Java source code, or null if not available
     */
    public String getSource(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }

        // Check cache first
        String cached = sourceCache.get(className);
        if (cached != null) {
            log.debug("Returning cached source for {}", className);
            return cached;
        }

        // First, try to load original source from -sources.jar
        // This gives us correct line numbers that match the bytecode
        String source = bytecodeLoader.loadSourceFromSourcesJar(className);
        if (source != null) {
            sourceCache.put(className, source);
            log.info("Loaded source from sources JAR for {} ({} chars)", className, source.length());
            return source;
        }

        // Fall back to decompilation
        // Note: Decompiled source may have different line numbers than the bytecode
        byte[] bytecode = bytecodeLoader.loadClass(className);
        if (bytecode == null) {
            log.debug("No bytecode found for {}", className);
            return null;
        }

        source = decompiler.decompile(bytecode, className);
        if (source != null) {
            sourceCache.put(className, source);
            log.info("Decompiled {} ({} chars) - line numbers may not match", className, source.length());
        }

        return source;
    }

    /**
     * Gets the decompiled source for a class given its source path.
     * Converts paths like "com/intuit/karate/core/ScenarioIterator.java" to class names.
     *
     * @param sourcePath Path in Java source format
     * @return Decompiled Java source code, or null if not available
     */
    public String getSourceByPath(String sourcePath) {
        String className = sourcePathToClassName(sourcePath);
        if (className == null) {
            return null;
        }
        return getSource(className);
    }

    /**
     * Converts a source path to a class name.
     * E.g., "com/intuit/karate/core/ScenarioIterator.java" -> "com.intuit.karate.core.ScenarioIterator"
     */
    public static String sourcePathToClassName(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return null;
        }

        // Remove .java extension
        String path = sourcePath;
        if (path.endsWith(".java")) {
            path = path.substring(0, path.length() - 5);
        }

        // Convert slashes to dots
        return path.replace('/', '.').replace('\\', '.');
    }

    /**
     * Checks if a source path looks like a Java class path that could be decompiled.
     * E.g., "com/intuit/karate/core/ScenarioIterator.java" returns true
     * E.g., "/Users/foo/src/main/java/MyClass.java" returns false (absolute path)
     */
    public static boolean isDecompilableSourcePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return false;
        }

        // Must end with .java
        if (!sourcePath.endsWith(".java")) {
            return false;
        }

        // Must not be an absolute path
        if (sourcePath.startsWith("/") || sourcePath.contains(":")) {
            return false;
        }

        // Must look like a package path (contains slashes)
        return sourcePath.contains("/");
    }

    /**
     * Clears the source cache.
     */
    public void clearCache() {
        sourceCache.clear();
        log.debug("Source cache cleared");
    }
}

