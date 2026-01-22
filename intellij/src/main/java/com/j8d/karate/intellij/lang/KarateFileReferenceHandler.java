package com.j8d.karate.intellij.lang;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides Ctrl+Click navigation for file references in Karate feature files.
 * Supports:
 * - classpath:path/to/file
 * - read('path/to/file') or read("path/to/file")
 * - read('@tagName') - jumps to tag in current file
 * - call read('...') patterns
 */
public class KarateFileReferenceHandler implements GotoDeclarationHandler {

    private static final Logger LOG = Logger.getInstance(KarateFileReferenceHandler.class);

    // Pattern to detect file references
    private static final Pattern CLASSPATH_PATTERN = Pattern.compile("classpath:([^\\s'\">)]+)");
    private static final Pattern READ_PATTERN = Pattern.compile("read\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern TAG_PATTERN = Pattern.compile("@([a-zA-Z0-9_-]+)");

    // Store the last resolved file name for the tooltip
    private String lastResolvedFileName = null;

    @Override
    public @Nullable String getActionText(@NotNull com.intellij.openapi.actionSystem.DataContext context) {
        if (lastResolvedFileName != null) {
            return "Go to " + lastResolvedFileName;
        }
        return null;
    }

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor) {

        LOG.info("KarateFileReferenceHandler: getGotoDeclarationTargets called, sourceElement=" + sourceElement + ", offset=" + offset);

        if (editor == null) {
            LOG.info("KarateFileReferenceHandler: editor is null");
            return null;
        }

        // Get the file from the editor's document
        Project project = editor.getProject();
        if (project == null) {
            LOG.info("KarateFileReferenceHandler: project is null");
            return null;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument())
        );

        if (psiFile == null) {
            LOG.info("KarateFileReferenceHandler: psiFile is null");
            return null;
        }

        LOG.info("KarateFileReferenceHandler: File type is " + psiFile.getFileType() + ", expected " + KarateFileType.INSTANCE);

        // Check if it's a Karate file
        if (!KarateFileType.INSTANCE.equals(psiFile.getFileType())) {
            LOG.info("KarateFileReferenceHandler: Not a Karate file: " + psiFile.getFileType());
            return null;
        }

        // Get the line text around the cursor
        String text = psiFile.getText();
        int lineStart = text.lastIndexOf('\n', offset) + 1;
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd == -1) lineEnd = text.length();
        String lineText = text.substring(lineStart, lineEnd);
        int positionInLine = offset - lineStart;

        LOG.info("KarateFileReferenceHandler: Checking line: '" + lineText + "' at position " + positionInLine);

        // Reset last resolved file name
        lastResolvedFileName = null;

        // Check for classpath: reference
        Matcher classpathMatcher = CLASSPATH_PATTERN.matcher(lineText);
        while (classpathMatcher.find()) {
            LOG.info("KarateFileReferenceHandler: Found classpath match: " + classpathMatcher.group() + " at " + classpathMatcher.start() + "-" + classpathMatcher.end());
            if (positionInLine >= classpathMatcher.start() && positionInLine <= classpathMatcher.end()) {
                String filePath = classpathMatcher.group(1);
                LOG.info("KarateFileReferenceHandler: Position in range, resolving: " + filePath);
                PsiElement target = resolveFilePathWithTag(project, psiFile, filePath);
                if (target != null) {
                    LOG.info("KarateFileReferenceHandler: Resolved to: " + target);
                    if (target instanceof PsiFile) {
                        lastResolvedFileName = ((PsiFile) target).getName();
                    } else {
                        lastResolvedFileName = filePath;
                    }
                    return new PsiElement[]{target};
                }
            }
        }

        // Check for read('...') reference
        Matcher readMatcher = READ_PATTERN.matcher(lineText);
        while (readMatcher.find()) {
            LOG.info("KarateFileReferenceHandler: Found read match: " + readMatcher.group() + " at " + readMatcher.start() + "-" + readMatcher.end());
            if (positionInLine >= readMatcher.start() && positionInLine <= readMatcher.end()) {
                String pathOrTag = readMatcher.group(1);
                LOG.info("KarateFileReferenceHandler: Position in range, path/tag: " + pathOrTag);

                // Check if it's a standalone @tag reference (no file path)
                if (pathOrTag.startsWith("@")) {
                    String tagName = pathOrTag.substring(1);
                    PsiElement target = findTagInFile(psiFile, tagName);
                    if (target != null) {
                        lastResolvedFileName = "@" + tagName;
                        return new PsiElement[]{target};
                    }
                } else {
                    // It's a file path (possibly with @tag suffix)
                    PsiElement target = resolveFilePathWithTag(project, psiFile, pathOrTag);
                    if (target != null) {
                        LOG.info("KarateFileReferenceHandler: Resolved to: " + target);
                        if (target instanceof PsiFile) {
                            lastResolvedFileName = ((PsiFile) target).getName();
                        } else {
                            lastResolvedFileName = pathOrTag;
                        }
                        return new PsiElement[]{target};
                    }
                }
            }
        }

        LOG.info("KarateFileReferenceHandler: No match found for offset " + offset);
        return null;
    }

    @Nullable
    private PsiElement resolveFilePath(@NotNull Project project, @NotNull PsiFile currentFile, @NotNull String filePath) {
        VirtualFile currentVFile = currentFile.getVirtualFile();
        if (currentVFile == null) return null;

        String basePath = project.getBasePath();
        LOG.info("KarateFileReferenceHandler: Resolving file path: " + filePath + ", project base: " + basePath);
        if (basePath == null) return null;

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
        LOG.info("KarateFileReferenceHandler: Resolved path (after stripping prefixes): " + resolvedPath);

        // Search paths to check
        String[] searchPaths = {
            resolvedPath,
            "src/test/java/" + resolvedPath,
            "src/test/resources/" + resolvedPath,
            "src/main/java/" + resolvedPath,
            "src/main/resources/" + resolvedPath,
        };

        // Also try relative to current file
        Path currentDir = Path.of(currentVFile.getPath()).getParent();

        for (String searchPath : searchPaths) {
            Path fullPath = Path.of(basePath, searchPath);
            LOG.info("KarateFileReferenceHandler: Checking: " + fullPath + " exists=" + Files.exists(fullPath));
            if (Files.exists(fullPath)) {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
                if (vf != null) {
                    LOG.info("KarateFileReferenceHandler: Found file: " + vf.getPath());
                    return PsiManager.getInstance(project).findFile(vf);
                }
            }
        }

        // Try relative path
        if (currentDir != null) {
            Path relativePath = currentDir.resolve(resolvedPath);
            LOG.info("KarateFileReferenceHandler: Checking relative: " + relativePath + " exists=" + Files.exists(relativePath));
            if (Files.exists(relativePath)) {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(relativePath.toString());
                if (vf != null) {
                    LOG.info("KarateFileReferenceHandler: Found file: " + vf.getPath());
                    return PsiManager.getInstance(project).findFile(vf);
                }
            }
        }

        LOG.info("KarateFileReferenceHandler: File not found for: " + filePath);
        return null;
    }

    @Nullable
    private PsiElement resolveFilePathWithTag(@NotNull Project project, @NotNull PsiFile currentFile, @NotNull String filePath) {
        // Check if path contains @tagName suffix
        int atIndex = filePath.indexOf('@');
        String tagName = null;
        String filePathOnly = filePath;

        if (atIndex > 0) {
            filePathOnly = filePath.substring(0, atIndex);
            tagName = filePath.substring(atIndex + 1);
        }

        // Resolve the file
        PsiElement fileElement = resolveFilePath(project, currentFile, filePathOnly);
        if (fileElement == null) {
            return null;
        }

        // If there's a tag and we found a file, find the tag in that file
        if (tagName != null && fileElement instanceof PsiFile) {
            PsiFile targetFile = (PsiFile) fileElement;

            // Check if it's the same file - if so, find tag in current file
            VirtualFile currentVFile = currentFile.getVirtualFile();
            VirtualFile targetVFile = targetFile.getVirtualFile();

            if (currentVFile != null && targetVFile != null && currentVFile.equals(targetVFile)) {
                // Same file - just find the tag
                PsiElement tagElement = findTagInFile(currentFile, tagName);
                if (tagElement != null) {
                    return tagElement;
                }
            } else {
                // Different file - find the tag in the target file
                PsiElement tagElement = findTagInFile(targetFile, tagName);
                if (tagElement != null) {
                    return tagElement;
                }
            }
        }

        return fileElement;
    }

    @Nullable
    private PsiElement findTagInFile(@NotNull PsiFile file, @NotNull String tagName) {
        String text = file.getText();
        Pattern tagPattern = Pattern.compile("^\\s*@" + Pattern.quote(tagName) + "\\b", Pattern.MULTILINE);
        Matcher matcher = tagPattern.matcher(text);

        if (matcher.find()) {
            int tagOffset = matcher.start() + matcher.group().indexOf("@");
            return file.findElementAt(tagOffset);
        }

        return null;
    }
}

