package com.j8d.karate.intellij.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A PSI reference for file paths in Karate feature files.
 * This enables Ctrl+Click navigation with underlines and tooltips.
 */
public class KarateFileReference extends PsiReferenceBase<PsiElement> {

    private final String filePath;

    public KarateFileReference(@NotNull PsiElement element, @NotNull TextRange rangeInElement, @NotNull String filePath) {
        super(element, rangeInElement);
        this.filePath = filePath;
    }

    @Override
    public @Nullable PsiElement resolve() {
        PsiFile containingFile = myElement.getContainingFile();
        if (containingFile == null) return null;

        String basePath = myElement.getProject().getBasePath();
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
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
                if (vf != null) {
                    return PsiManager.getInstance(myElement.getProject()).findFile(vf);
                }
            }
        }

        // Try relative to current file
        VirtualFile currentVFile = containingFile.getVirtualFile();
        if (currentVFile != null) {
            Path currentDir = Path.of(currentVFile.getPath()).getParent();
            if (currentDir != null) {
                Path relativePath = currentDir.resolve(resolvedPath);
                if (Files.exists(relativePath)) {
                    VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(relativePath.toString());
                    if (vf != null) {
                        return PsiManager.getInstance(myElement.getProject()).findFile(vf);
                    }
                }
            }
        }

        return null;
    }

    @Override
    public Object @NotNull [] getVariants() {
        // Could provide completion suggestions here
        return EMPTY_ARRAY;
    }
}

