package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.util.SlowOperations;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single stack frame during debugging.
 * Maps DAP frame to IntelliJ's XStackFrame.
 */
public class KarateStackFrame extends XStackFrame {

    private static final Logger LOG = Logger.getInstance(KarateStackFrame.class);

    private final KarateDebugProcess debugProcess;
    private final int frameId;
    private final String name;
    private final String sourcePath;
    private final int line;
    private XSourcePosition sourcePosition;

    public KarateStackFrame(KarateDebugProcess debugProcess, JsonObject frame) {
        this.debugProcess = debugProcess;
        this.frameId = frame.get("id").getAsInt();
        this.name = frame.has("name") ? frame.get("name").getAsString() : "Unknown";

        // Parse source location
        if (frame.has("source") && frame.getAsJsonObject("source").has("path")) {
            this.sourcePath = frame.getAsJsonObject("source").get("path").getAsString();
        } else {
            this.sourcePath = null;
        }

        this.line = frame.has("line") ? frame.get("line").getAsInt() : 1;

        // Create source position
        if (sourcePath != null) {
            VirtualFile file = resolveSourceFile(sourcePath);
            if (file != null) {
                // DAP uses 1-based lines, IntelliJ uses 0-based
                this.sourcePosition = XDebuggerUtil.getInstance().createPosition(file, line - 1);
            }
        }
    }

    /**
     * Resolves a source path to a VirtualFile.
     * First tries LocalFileSystem for absolute paths, then falls back to
     * IntelliJ's Java class resolution for library sources.
     */
    private VirtualFile resolveSourceFile(String path) {
        // Normalize to system-independent form (forward slashes) for IntelliJ's VirtualFile API
        // This handles Windows backslash-separated paths from the debug server
        String normalizedPath = path.replace('\\', '/');

        // First, try direct file system lookup (works for absolute paths)
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(normalizedPath);
        if (file != null) {
            return file;
        }

        // If path looks like a Java class path (e.g., com/intuit/karate/core/ScenarioEngine.java),
        // try to resolve it using IntelliJ's Java PSI.
        // Check for absolute paths on both Unix (starts with /) and Windows (e.g., C:\ or C:/)
        boolean isAbsolutePath = normalizedPath.startsWith("/") ||
                                 (normalizedPath.length() > 2 && normalizedPath.charAt(1) == ':');
        if (normalizedPath.endsWith(".java") && !isAbsolutePath) {
            String className = pathToClassName(normalizedPath);
            if (className != null) {
                file = findClassSourceFile(className);
                if (file != null) {
                    LOG.debug("Resolved " + normalizedPath + " to " + file.getPath() + " via JavaPsiFacade");
                    return file;
                }
            }
        }

        return null;
    }

    /**
     * Converts a Java source path to a fully qualified class name.
     * e.g., "com/intuit/karate/core/ScenarioEngine.java" -> "com.intuit.karate.core.ScenarioEngine"
     */
    private String pathToClassName(String path) {
        if (path == null || !path.endsWith(".java")) {
            return null;
        }

        // Remove .java extension and convert slashes to dots
        String className = path.substring(0, path.length() - 5).replace('/', '.').replace('\\', '.');

        // Handle inner classes (e.g., Outer$Inner)
        // The path might be Outer.java but we're looking for Outer$Inner
        // For now, just return the outer class name

        return className;
    }

    /**
     * Finds the source file for a Java class using IntelliJ's PSI.
     * This works for both project classes and library classes with attached sources.
     */
    private VirtualFile findClassSourceFile(String className) {
        Project project = debugProcess.getSession().getProject();

        // Do the PSI lookup in a ReadAction. We use SlowOperations.allowSlowOperations() to suppress
        // the EDT warning because this is called during stack frame construction which happens
        // on the EDT during debug events. The PSI lookup is generally fast for cached data.
        // TODO: Consider refactoring to compute source positions asynchronously in the future.
        VirtualFile result = SlowOperations.allowSlowOperations(
            () -> ReadAction.compute(() -> {
                try {
                    // Search in all scopes including libraries
                    GlobalSearchScope scope = GlobalSearchScope.allScope(project);
                    PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope);

                    if (psiClass != null) {
                        // Use getNavigationElement() to get the source file instead of the class file.
                        // This is what IntelliJ uses for "Go to Declaration" and properly resolves
                        // to source files in src.zip or attached sources.
                        PsiElement navigationElement = psiClass.getNavigationElement();
                        if (navigationElement != null) {
                            PsiFile containingFile = navigationElement.getContainingFile();
                            if (containingFile != null) {
                                VirtualFile vf = containingFile.getVirtualFile();
                                LOG.debug("Resolved " + className + " -> " + (vf != null ? vf.getPath() : "null"));
                                return vf;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("Failed to resolve class " + className + ": " + e.getMessage());
                }
                return null;
            }));

        // Check for missing sources OUTSIDE the ReadAction to avoid threading issues
        if (result != null) {
            SourceDownloadService downloadService = SourceDownloadService.getInstance(project);
            if (downloadService.isMissingSources(result)) {
                LOG.debug("Missing sources for " + className + ", file: " + result.getPath());
                downloadService.notifyMissingSources(className);
            }
        }

        return result;
    }
    
    @Override
    public @Nullable XSourcePosition getSourcePosition() {
        return sourcePosition;
    }
    
    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        component.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        if (sourcePath != null) {
            String fileName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
            component.append(" (" + fileName + ":" + line + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        component.setIcon(AllIcons.Debugger.Frame);
    }
    
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        // Fetch scopes for this frame
        debugProcess.getDapClient().getScopes(frameId).thenAccept(response -> {
            XValueChildrenList children = new XValueChildrenList();
            
            if (response != null && response.has("scopes")) {
                JsonArray scopes = response.getAsJsonArray("scopes");
                for (int i = 0; i < scopes.size(); i++) {
                    JsonObject scope = scopes.get(i).getAsJsonObject();
                    String scopeName = scope.get("name").getAsString();
                    int variablesReference = scope.get("variablesReference").getAsInt();
                    
                    children.addTopGroup(new KarateVariableGroup(debugProcess, scopeName, variablesReference));
                }
            }
            
            node.addChildren(children, true);
        }).exceptionally(e -> {
            node.setErrorMessage("Error loading variables: " + e.getMessage());
            return null;
        });
    }
    
    public int getFrameId() {
        return frameId;
    }
}

