package com.j8d.karate.intellij.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contributes PSI references for file paths in Karate feature files.
 * This enables Ctrl+Click navigation with underlines and tooltips.
 */
public class KarateReferenceContributor extends PsiReferenceContributor {

    private static final Pattern CLASSPATH_PATTERN = Pattern.compile("classpath:([^\\s'\">)]+)");
    private static final Pattern READ_PATTERN = Pattern.compile("read\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiPlainText.class),
            new PsiReferenceProvider() {
                @Override
                public PsiReference @NotNull [] getReferencesByElement(
                        @NotNull PsiElement element,
                        @NotNull ProcessingContext context) {
                    
                    PsiFile file = element.getContainingFile();
                    if (file == null || !KarateFileType.INSTANCE.equals(file.getFileType())) {
                        return PsiReference.EMPTY_ARRAY;
                    }

                    String text = element.getText();
                    List<PsiReference> references = new ArrayList<>();

                    // Find classpath: references
                    Matcher classpathMatcher = CLASSPATH_PATTERN.matcher(text);
                    while (classpathMatcher.find()) {
                        String filePath = classpathMatcher.group(1);
                        int start = classpathMatcher.start(1);
                        int end = classpathMatcher.end(1);
                        TextRange range = new TextRange(start, end);
                        references.add(new KarateFileReference(element, range, filePath));
                    }

                    // Find read('...') references
                    Matcher readMatcher = READ_PATTERN.matcher(text);
                    while (readMatcher.find()) {
                        String pathOrTag = readMatcher.group(1);
                        // Skip @tag references for now (handled separately)
                        if (!pathOrTag.startsWith("@")) {
                            int start = readMatcher.start(1);
                            int end = readMatcher.end(1);
                            TextRange range = new TextRange(start, end);
                            references.add(new KarateFileReference(element, range, pathOrTag));
                        }
                    }

                    return references.toArray(PsiReference.EMPTY_ARRAY);
                }
            }
        );
    }
}

