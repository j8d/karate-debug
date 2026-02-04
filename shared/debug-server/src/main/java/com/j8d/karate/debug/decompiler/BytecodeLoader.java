package com.j8d.karate.debug.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

/**
 * Loads Java class file bytecode and source files from the classpath.
 * Searches JAR files and directories for the specified class.
 * Also supports loading source from -sources.jar files, downloading them if needed.
 */
public class BytecodeLoader {
    private static final Logger log = LoggerFactory.getLogger(BytecodeLoader.class);

    // Pattern to extract Maven coordinates from .m2/repository path
    // e.g., /Users/ryan/.m2/repository/com/jayway/jsonpath/json-path/2.9.0/json-path-2.9.0.jar
    private static final Pattern MAVEN_REPO_PATTERN = Pattern.compile(
            ".*/\\.m2/repository/(.+)/([^/]+)/([^/]+)/\\2-\\3\\.jar$"
    );

    private static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";

    private final List<String> classpathEntries;

    // Track JARs we've already tried to download sources for (to avoid repeated attempts)
    private final Set<String> attemptedDownloads = Collections.synchronizedSet(new HashSet<>());

    // Path to JDK src.zip (lazily initialized)
    private Path jdkSourceZip;
    private boolean jdkSourceZipChecked = false;

    /**
     * Creates a BytecodeLoader with the given classpath entries.
     *
     * @param classpathEntries List of JAR file paths or directory paths
     */
    public BytecodeLoader(List<String> classpathEntries) {
        this.classpathEntries = classpathEntries != null ? classpathEntries : Collections.emptyList();
        log.debug("BytecodeLoader initialized with {} classpath entries", this.classpathEntries.size());
    }

    /**
     * Loads the bytecode for a class.
     *
     * @param className Fully qualified class name (e.g., "com.intuit.karate.core.ScenarioIterator")
     * @return The class file bytecode, or null if not found
     */
    public byte[] loadClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }

        // Convert class name to resource path
        String resourcePath = className.replace('.', '/') + ".class";
        log.debug("Searching for class file: {}", resourcePath);

        for (String entry : classpathEntries) {
            byte[] bytecode = loadBytesFromEntry(entry, resourcePath);
            if (bytecode != null) {
                log.debug("Found class {} in {}", className, entry);
                return bytecode;
            }
        }

        log.debug("Class not found in classpath: {}", className);
        return null;
    }

    /**
     * Loads the original source file for a class from a -sources.jar.
     * This provides the original source with correct line numbers.
     * If the sources JAR doesn't exist locally, attempts to download it from Maven Central.
     *
     * @param className Fully qualified class name (e.g., "com.jayway.jsonpath.JsonPath")
     * @return The source code as a String, or null if not found
     */
    public String loadSourceFromSourcesJar(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }

        // Convert class name to source path
        String sourcePath = className.replace('.', '/') + ".java";
        log.debug("Searching for source file in sources JARs: {}", sourcePath);

        // First, check if this is a JDK class and try to load from src.zip
        if (isJdkClass(className)) {
            String jdkSource = loadSourceFromJdkSrcZip(sourcePath);
            if (jdkSource != null) {
                return jdkSource;
            }
        }

        for (String entry : classpathEntries) {
            if (!entry.endsWith(".jar")) {
                continue;
            }

            // Try to find corresponding -sources.jar
            String sourcesJarPath = getSourcesJarPath(entry);
            if (sourcesJarPath == null) {
                continue;
            }

            Path sourcesJar = Paths.get(sourcesJarPath);

            // If sources JAR doesn't exist, try to download it
            if (!Files.exists(sourcesJar)) {
                if (!attemptedDownloads.contains(entry)) {
                    attemptedDownloads.add(entry);
                    downloadSourcesJar(entry, sourcesJarPath);
                }
                // Check again after download attempt
                if (!Files.exists(sourcesJar)) {
                    continue;
                }
            }

            // Try to load source from the sources JAR
            byte[] sourceBytes = loadBytesFromJar(sourcesJar, sourcePath);
            if (sourceBytes != null) {
                String source = new String(sourceBytes, StandardCharsets.UTF_8);
                log.debug("Found source for {} in {}", className, sourcesJarPath);
                return source;
            }
        }

        log.debug("Source not found in any sources JAR: {}", className);
        return null;
    }

    /**
     * Checks if a class is a JDK class (java.*, javax.*, jdk.*, sun.*, com.sun.*).
     */
    private boolean isJdkClass(String className) {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("jdk.") ||
               className.startsWith("sun.") ||
               className.startsWith("com.sun.");
    }

    /**
     * Loads source from the JDK's src.zip file.
     * Lazily locates src.zip in the JDK installation.
     *
     * @param sourcePath The source file path (e.g., "java/util/ArrayList.java")
     * @return The source code, or null if not found
     */
    private String loadSourceFromJdkSrcZip(String sourcePath) {
        // Lazily find the JDK src.zip
        if (!jdkSourceZipChecked) {
            jdkSourceZipChecked = true;
            jdkSourceZip = findJdkSrcZip();
            if (jdkSourceZip != null) {
                log.info("Found JDK src.zip at: {}", jdkSourceZip);
            } else {
                log.debug("JDK src.zip not found");
            }
        }

        if (jdkSourceZip == null) {
            return null;
        }

        // JDK 9+ src.zip has modular structure: module/package/Class.java
        // e.g., java.base/java/lang/Object.java
        // We need to try common module prefixes
        String[] modulePrefixes = {
            "java.base/",           // Core classes (java.lang, java.util, java.io, etc.)
            "java.logging/",        // java.util.logging
            "java.sql/",            // java.sql
            "java.xml/",            // javax.xml
            "java.naming/",         // javax.naming
            "java.desktop/",        // javax.swing, java.awt
            "java.net.http/",       // java.net.http (HttpClient)
            "java.compiler/",       // javax.tools
            "java.management/",     // javax.management
            "jdk.internal.vm.ci/",  // jdk.internal
            ""                      // Also try without prefix (older JDKs)
        };

        for (String prefix : modulePrefixes) {
            String fullPath = prefix + sourcePath;
            byte[] sourceBytes = loadBytesFromJar(jdkSourceZip, fullPath);
            if (sourceBytes != null) {
                String source = new String(sourceBytes, StandardCharsets.UTF_8);
                log.debug("Found JDK source for {} in src.zip (path: {})", sourcePath, fullPath);
                return source;
            }
        }

        log.debug("JDK source not found in src.zip for: {}", sourcePath);
        return null;
    }

    /**
     * Finds the JDK src.zip file.
     * Looks in common locations based on java.home system property.
     *
     * @return Path to src.zip, or null if not found
     */
    private Path findJdkSrcZip() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            log.debug("java.home system property not set");
            return null;
        }

        Path javaHomePath = Paths.get(javaHome);

        // Common locations for src.zip:
        // 1. $JAVA_HOME/lib/src.zip (JDK 9+)
        // 2. $JAVA_HOME/../lib/src.zip (if java.home points to jre subdirectory)
        // 3. $JAVA_HOME/src.zip (some distributions)
        Path[] candidates = {
            javaHomePath.resolve("lib/src.zip"),
            javaHomePath.getParent().resolve("lib/src.zip"),
            javaHomePath.resolve("src.zip"),
            javaHomePath.getParent().resolve("src.zip"),
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        log.debug("src.zip not found in any expected location under {}", javaHome);
        return null;
    }

    /**
     * Attempts to download a sources JAR from Maven Central.
     * Only works for JARs in the Maven local repository (.m2/repository).
     */
    private void downloadSourcesJar(String jarPath, String sourcesJarPath) {
        // Parse Maven coordinates from the path
        Matcher matcher = MAVEN_REPO_PATTERN.matcher(jarPath);
        if (!matcher.matches()) {
            log.debug("JAR path doesn't match Maven repository pattern: {}", jarPath);
            return;
        }

        String groupPath = matcher.group(1);  // e.g., "com/jayway/jsonpath"
        String artifactId = matcher.group(2); // e.g., "json-path"
        String version = matcher.group(3);    // e.g., "2.9.0"

        // Construct Maven Central URL for sources JAR
        String sourcesUrl = String.format("%s/%s/%s/%s/%s-%s-sources.jar",
                MAVEN_CENTRAL_URL, groupPath, artifactId, version, artifactId, version);

        log.info("Downloading sources JAR from: {}", sourcesUrl);

        try {
            URL url = new URL(sourcesUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "karate-debug/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // Download to temp file first, then move atomically
                Path tempFile = Files.createTempFile("sources", ".jar");
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }

                // Move to final location
                Path targetPath = Paths.get(sourcesJarPath);
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Downloaded sources JAR: {}", sourcesJarPath);
            } else {
                log.debug("Sources JAR not available (HTTP {}): {}", responseCode, sourcesUrl);
            }
            conn.disconnect();
        } catch (Exception e) {
            log.debug("Failed to download sources JAR: {} - {}", sourcesUrl, e.getMessage());
        }
    }

    /**
     * Converts a JAR path to its corresponding -sources.jar path.
     * E.g., "json-path-2.9.0.jar" -> "json-path-2.9.0-sources.jar"
     */
    private String getSourcesJarPath(String jarPath) {
        if (!jarPath.endsWith(".jar")) {
            return null;
        }
        // Remove .jar and add -sources.jar
        return jarPath.substring(0, jarPath.length() - 4) + "-sources.jar";
    }

    private byte[] loadBytesFromEntry(String entry, String resourcePath) {
        Path path = Paths.get(entry);

        if (!Files.exists(path)) {
            return null;
        }

        if (Files.isDirectory(path)) {
            return loadBytesFromDirectory(path, resourcePath);
        } else if (entry.endsWith(".jar")) {
            return loadBytesFromJar(path, resourcePath);
        }

        return null;
    }

    private byte[] loadBytesFromDirectory(Path directory, String resourcePath) {
        Path file = directory.resolve(resourcePath);
        if (Files.exists(file)) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                log.warn("Failed to read file: {}", file, e);
            }
        }
        return null;
    }

    private byte[] loadBytesFromJar(Path jarPath, String resourcePath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry jarEntry = jarFile.getJarEntry(resourcePath);
            if (jarEntry != null) {
                try (InputStream is = jarFile.getInputStream(jarEntry)) {
                    return is.readAllBytes();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read from JAR: {}", jarPath, e);
        }
        return null;
    }

    /**
     * Creates a BytecodeLoader from a classpath string (path separator separated).
     */
    public static BytecodeLoader fromClasspath(String classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return new BytecodeLoader(Collections.emptyList());
        }
        String[] entries = classpath.split(File.pathSeparator);
        return new BytecodeLoader(Arrays.asList(entries));
    }
}

