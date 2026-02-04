package com.j8d.karate.debug.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matches JavaScript source content to actual .js files in the workspace.
 * 
 * This enables source content matching for JavaScript debugging when Karate
 * evaluates JS files via inline eval (which creates "Unnamed" sources in GraalVM).
 * 
 * The matcher:
 * 1. Scans the workspace for .js files on initialization
 * 2. Stores content -> file path mappings
 * 3. When GraalVM reports an "Unnamed" source, matches its content to a known file
 * 4. Provides bidirectional mapping between sourceReferences and file paths
 */
public class JavaScriptSourceMatcher {
    
    private static final Logger log = LoggerFactory.getLogger(JavaScriptSourceMatcher.class);
    
    // Content hash -> file path (for matching "Unnamed" sources to files)
    private final Map<String, Path> contentToPath = new ConcurrentHashMap<>();

    // sourceReference -> file path (runtime mapping from DAP)
    private final Map<Integer, Path> sourceRefToPath = new ConcurrentHashMap<>();
    
    // file path -> sourceReference (reverse mapping for breakpoints)
    private final Map<Path, Integer> pathToSourceRef = new ConcurrentHashMap<>();
    
    private final Path workspaceRoot;
    
    /**
     * Creates a new JavaScriptSourceMatcher for the given workspace.
     * 
     * @param workspaceRoot The root directory to scan for .js files
     */
    public JavaScriptSourceMatcher(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }
    
    /**
     * Scans the workspace for .js files and builds the content mapping.
     * Call this once at debug session start.
     */
    public void scanWorkspace() {
        log.trace("Scanning workspace for .js files: {}", workspaceRoot);
        
        if (!Files.exists(workspaceRoot)) {
            log.warn("Workspace root does not exist: {}", workspaceRoot);
            return;
        }
        
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".js") && !isExcluded(file)) {
                        indexJsFile(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip common directories that shouldn't contain user JS files
                    String name = dir.getFileName().toString();
                    if (name.equals("node_modules") || name.equals(".git") || 
                        name.equals("target") || name.equals("build")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.trace("Failed to visit file: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error scanning workspace for .js files", e);
        }
        
        log.trace("Indexed {} JavaScript files", contentToPath.size());
    }
    
    /**
     * Indexes a single .js file by reading its content.
     */
    private void indexJsFile(Path file) {
        try {
            String content = Files.readString(file);
            Path normalizedPath = file.toAbsolutePath().normalize();

            // Use content as key (exact match)
            contentToPath.put(content, normalizedPath);

            log.trace("Indexed JS file: {} ({} chars)", normalizedPath, content.length());
        } catch (IOException e) {
            log.trace("Failed to read JS file: {}", file);
        }
    }
    
    /**
     * Checks if a file should be excluded from indexing.
     */
    private boolean isExcluded(Path file) {
        String path = file.toString();
        // Exclude minified files, vendor files, etc.
        return path.contains(".min.js") || 
               path.contains("/vendor/") ||
               path.contains("/dist/");
    }
    
    /**
     * Attempts to match source content to a known .js file.
     *
     * Karate wraps JavaScript file content in parentheses when evaluating,
     * so we try multiple matching strategies:
     * 1. Exact match
     * 2. Strip outer parentheses wrapper: (content) -> content
     * 3. Strip trailing whitespace from unwrapped content
     * 4. Try matching with normalized (trimmed) content on both sides
     *
     * @param content The source content from GraalVM DAP
     * @return The matching file path, or empty if no match found
     */
    public Optional<Path> matchContent(String content) {
        log.trace("Attempting to match content ({} chars), starts with: {}",
                content.length(),
                content.substring(0, Math.min(50, content.length())).replace("\n", "\\n"));

        // Strategy 1: Exact match
        Path match = contentToPath.get(content);
        if (match != null) {
            log.trace("Content matched to file (exact): {}", match);
            return Optional.of(match);
        }

        // Strategy 2: Strip outer parentheses wrapper that Karate adds
        // Karate wraps content like: (originalContent\n) or (originalContent\n\n)
        if (content.startsWith("(") && content.endsWith(")")) {
            String unwrapped = content.substring(1, content.length() - 1);
            log.trace("Trying unwrapped content ({} chars)", unwrapped.length());

            match = contentToPath.get(unwrapped);
            if (match != null) {
                log.trace("Content matched to file (unwrapped parens): {}", match);
                return Optional.of(match);
            }

            // Strategy 3: Strip trailing whitespace (newlines) from unwrapped content
            String trimmedEnd = unwrapped.stripTrailing();
            log.trace("Trying trimmed content ({} chars)", trimmedEnd.length());

            match = contentToPath.get(trimmedEnd);
            if (match != null) {
                log.trace("Content matched to file (unwrapped + trimmed): {}", match);
                return Optional.of(match);
            }

            // Strategy 4: Try matching against trimmed file content
            // Some files may have trailing whitespace that differs
            for (Map.Entry<String, Path> entry : contentToPath.entrySet()) {
                String fileContent = entry.getKey();
                if (fileContent.stripTrailing().equals(trimmedEnd)) {
                    log.trace("Content matched to file (both trimmed): {}", entry.getValue());
                    return Optional.of(entry.getValue());
                }
            }
        }

        log.trace("No match found for content ({} chars)", content.length());
        return Optional.empty();
    }
    
    /**
     * Registers a mapping between a DAP sourceReference and a file path.
     * Call this when an "Unnamed" source is matched to a file.
     */
    public void registerMapping(int sourceReference, Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        sourceRefToPath.put(sourceReference, normalized);
        pathToSourceRef.put(normalized, sourceReference);
        log.trace("Registered source mapping: ref={} -> {}", sourceReference, normalized);
    }
    
    /**
     * Gets the file path for a sourceReference.
     */
    public Optional<Path> getPathForSourceRef(int sourceReference) {
        return Optional.ofNullable(sourceRefToPath.get(sourceReference));
    }
    
    /**
     * Gets the sourceReference for a file path.
     */
    public Optional<Integer> getSourceRefForPath(Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        return Optional.ofNullable(pathToSourceRef.get(normalized));
    }
    
    /**
     * Gets the sourceReference for a file path string.
     */
    public Optional<Integer> getSourceRefForPath(String filePath) {
        return getSourceRefForPath(Path.of(filePath));
    }
    
    /**
     * Checks if a file path has been mapped to a sourceReference.
     */
    public boolean hasMapping(Path filePath) {
        Path normalized = filePath.toAbsolutePath().normalize();
        return pathToSourceRef.containsKey(normalized);
    }
    
    /**
     * Checks if a file path has been mapped to a sourceReference.
     */
    public boolean hasMapping(String filePath) {
        return hasMapping(Path.of(filePath));
    }
    
    /**
     * Gets the number of indexed .js files.
     */
    public int getIndexedFileCount() {
        return contentToPath.size();
    }
    
    /**
     * Gets the number of active source mappings.
     */
    public int getMappingCount() {
        return sourceRefToPath.size();
    }
}

