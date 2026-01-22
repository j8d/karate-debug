package com.j8d.karate.intellij.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Annotator that adds underline styling to file references in Karate feature files.
 * This provides visual indication that file paths are clickable.
 */
public class KarateFileReferenceAnnotator implements Annotator {

    private static final Pattern CLASSPATH_PATTERN = Pattern.compile("classpath:([^\\s'\">)]+)");
    private static final Pattern READ_PATTERN = Pattern.compile("read\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    // Custom text attributes for file reference links
    private static final TextAttributesKey FILE_LINK = TextAttributesKey.createTextAttributesKey(
            "KARATE_FILE_LINK", DefaultLanguageHighlighterColors.STRING);

    private static final TextAttributes LINK_ATTRIBUTES;
    static {
        LINK_ATTRIBUTES = new TextAttributes();
        LINK_ATTRIBUTES.setForegroundColor(JBColor.namedColor("Link.activeForeground", new JBColor(0x589DF6, 0x589DF6)));
        LINK_ATTRIBUTES.setEffectColor(JBColor.namedColor("Link.activeForeground", new JBColor(0x589DF6, 0x589DF6)));
        LINK_ATTRIBUTES.setEffectType(EffectType.LINE_UNDERSCORE);
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        PsiFile file = element.getContainingFile();
        if (file == null || !KarateFileType.INSTANCE.equals(file.getFileType())) {
            return;
        }

        // Only process the file-level element to avoid duplicate annotations
        if (!(element instanceof PsiFile)) {
            return;
        }

        String text = element.getText();
        int elementOffset = element.getTextRange().getStartOffset();

        // Track annotated ranges to avoid duplicates
        List<int[]> annotatedRanges = new ArrayList<>();

        // Find and annotate classpath: references
        Matcher classpathMatcher = CLASSPATH_PATTERN.matcher(text);
        while (classpathMatcher.find()) {
            String filePath = classpathMatcher.group(1);
            if (canResolveFile(file, filePath)) {
                int start = elementOffset + classpathMatcher.start(1);
                int end = elementOffset + classpathMatcher.end(1);
                if (!hasOverlap(annotatedRanges, start, end)) {
                    annotatedRanges.add(new int[]{start, end});
                    annotateFileReference(holder, new TextRange(start, end), filePath, file);
                }
            }
        }

        // Find and annotate read('...') references
        Matcher readMatcher = READ_PATTERN.matcher(text);
        while (readMatcher.find()) {
            String pathOrTag = readMatcher.group(1);
            // Skip standalone @tag references (no file path)
            if (!pathOrTag.startsWith("@") && canResolveFile(file, pathOrTag)) {
                int start = elementOffset + readMatcher.start(1);
                int end = elementOffset + readMatcher.end(1);
                if (!hasOverlap(annotatedRanges, start, end)) {
                    annotatedRanges.add(new int[]{start, end});
                    annotateFileReference(holder, new TextRange(start, end), pathOrTag, file);
                }
            }
        }
    }

    private void annotateFileReference(@NotNull AnnotationHolder holder, @NotNull TextRange range,
                                        @NotNull String filePath, @NotNull PsiFile containingFile) {
        // Check if path contains @tagName suffix
        int atIndex = filePath.indexOf('@');
        String tagName = null;
        String filePathOnly = filePath;

        if (atIndex > 0) {
            filePathOnly = filePath.substring(0, atIndex);
            tagName = filePath.substring(atIndex + 1);
        }

        // Get the file name for display
        String fileName = filePathOnly.contains("/") ? filePathOnly.substring(filePathOnly.lastIndexOf('/') + 1) : filePathOnly;

        // Determine tooltip based on whether it's same file or different file with tag
        String tooltip;
        if (tagName != null) {
            boolean isSameFile = isSameFile(containingFile, filePathOnly);
            if (isSameFile) {
                tooltip = "Cmd+Click to jump to @" + tagName;
            } else {
                tooltip = "Cmd+Click to open " + fileName + " and jump to @" + tagName;
            }
        } else {
            tooltip = "Cmd+Click to open " + fileName;
        }

        holder.newAnnotation(HighlightSeverity.INFORMATION, tooltip)
                .range(range)
                .enforcedTextAttributes(LINK_ATTRIBUTES)
                .create();
    }

    private boolean isSameFile(@NotNull PsiFile containingFile, @NotNull String filePath) {
        String basePath = containingFile.getProject().getBasePath();
        if (basePath == null) return false;

        // Strip classpath: prefix if present
        String resolvedPath = filePath;
        if (resolvedPath.startsWith("classpath:")) {
            resolvedPath = resolvedPath.substring("classpath:".length());
        }

        // Get the containing file's path
        VirtualFile currentVFile = containingFile.getVirtualFile();
        if (currentVFile == null) return false;
        String currentPath = currentVFile.getPath();

        // Search paths to check
        String[] searchPaths = {
            resolvedPath,
            "src/test/java/" + resolvedPath,
            "src/test/resources/" + resolvedPath,
            "src/main/java/" + resolvedPath,
            "src/main/resources/" + resolvedPath,
        };

        for (String searchPath : searchPaths) {
            Path fullPath = Path.of(basePath, searchPath);
            if (Files.exists(fullPath) && fullPath.toString().equals(currentPath)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasOverlap(List<int[]> ranges, int start, int end) {
        for (int[] range : ranges) {
            // Check if ranges overlap: !(end <= range[0] || start >= range[1])
            if (!(end <= range[0] || start >= range[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean canResolveFile(@NotNull PsiFile containingFile, @NotNull String filePath) {
        String basePath = containingFile.getProject().getBasePath();
        if (basePath == null) return false;

        // Strip classpath: prefix if present
        String resolvedPath = filePath;
        if (resolvedPath.startsWith("classpath:")) {
            resolvedPath = resolvedPath.substring("classpath:".length());
        }

        // Strip leading slash if present (e.g., /events/file.json -> events/file.json)
        if (resolvedPath.startsWith("/")) {
            resolvedPath = resolvedPath.substring(1);
        }

        // Strip @tagName suffix if present (e.g., file.feature@tagName -> file.feature)
        int atIndex = resolvedPath.indexOf('@');
        if (atIndex > 0) {
            resolvedPath = resolvedPath.substring(0, atIndex);
        }

        // Search paths to check
        String[] searchPaths = {
            resolvedPath,
            "src/test/java/" + resolvedPath,
            "src/test/resources/" + resolvedPath,
            "src/main/java/" + resolvedPath,
            "src/main/resources/" + resolvedPath,
        };

        for (String searchPath : searchPaths) {
            Path fullPath = Path.of(basePath, searchPath);
            if (Files.exists(fullPath)) {
                return true;
            }
        }

        // Try relative to current file
        VirtualFile currentVFile = containingFile.getVirtualFile();
        if (currentVFile != null) {
            Path currentDir = Path.of(currentVFile.getPath()).getParent();
            if (currentDir != null) {
                Path relativePath = currentDir.resolve(resolvedPath);
                if (Files.exists(relativePath)) {
                    return true;
                }
            }
        }

        return false;
    }
}

