package com.j8d.karate.intellij.project;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for detecting and managing Karate projects.
 * Provides detection for Maven/Gradle projects with Karate dependencies,
 * classpath resolution, and environment discovery.
 */
@Service(Service.Level.PROJECT)
public final class KarateProjectService {

    private static final Logger LOG = Logger.getInstance(KarateProjectService.class);

    private static final Pattern ENV_PATTERN = Pattern.compile(
        "function\\s*\\(\\s*\\)\\s*\\{[^}]*karate\\.env[^}]*\\}|" +
        "if\\s*\\(\\s*env\\s*==\\s*['\"]([^'\"]+)['\"]\\s*\\)"
    );

    private final Project project;
    private boolean isKarateProject = false;
    private boolean initialized = false;
    private String karateVersion = null;
    private String projectType = null; // "maven" or "gradle"
    private List<VirtualFile> karateConfigFiles = new ArrayList<>();
    private Set<String> detectedEnvironments = new HashSet<>();
    private List<VirtualFile> featureFiles = new ArrayList<>();

    public KarateProjectService(@NotNull Project project) {
        this.project = project;
    }

    public static KarateProjectService getInstance(@NotNull Project project) {
        return project.getService(KarateProjectService.class);
    }

    /**
     * Check if this project contains Karate dependencies.
     * Returns cached result only - does not trigger detection on EDT.
     */
    public boolean isKarateProject() {
        // Don't trigger detection on EDT - just return cached result
        // Detection is triggered by startup activity in background
        return isKarateProject;
    }

    /**
     * Check if detection has completed.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Force re-detection of Karate project status.
     * Must be called from a background thread (not EDT).
     */
    public void refresh() {
        initialized = false;
        karateVersion = null;
        projectType = null;
        karateConfigFiles.clear();
        detectedEnvironments.clear();
        featureFiles.clear();
        detectKarateProject();
    }

    /**
     * Get the detected Karate version, if any.
     * Returns cached result only - does not trigger detection.
     */
    @Nullable
    public String getKarateVersion() {
        return karateVersion;
    }

    /**
     * Get the project build system type ("maven", "gradle", or null).
     * Returns cached result only - does not trigger detection.
     */
    @Nullable
    public String getProjectType() {
        return projectType;
    }

    /**
     * Get all detected karate-config.js files in the project.
     */
    @NotNull
    public List<VirtualFile> getKarateConfigFiles() {
        if (!initialized) {
            detectKarateProject();
        }
        return Collections.unmodifiableList(karateConfigFiles);
    }

    /**
     * Get all detected Karate environments from karate-config.js files.
     */
    @NotNull
    public Set<String> getDetectedEnvironments() {
        if (!initialized) {
            detectKarateProject();
        }
        return Collections.unmodifiableSet(detectedEnvironments);
    }

    /**
     * Get all .feature files in the project.
     */
    @NotNull
    public List<VirtualFile> getFeatureFiles() {
        if (!initialized) {
            detectKarateProject();
        }
        return Collections.unmodifiableList(featureFiles);
    }

    /**
     * Get the classpath for running Karate tests.
     * This includes project dependencies and compiled classes.
     */
    @NotNull
    public String getClasspath() {
        Set<String> classpathEntries = new LinkedHashSet<>();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            OrderEnumerator enumerator = ModuleRootManager.getInstance(module)
                .orderEntries()
                .withoutSdk()
                .recursively();

            // Get compiled output paths
            for (VirtualFile root : enumerator.getClassesRoots()) {
                String path = root.getPath();
                // VirtualFile paths for JARs include "!/" suffix - remove it
                if (path.endsWith("!/")) {
                    path = path.substring(0, path.length() - 2);
                } else if (path.contains("!/")) {
                    // Handle nested paths like "foo.jar!/some/path" - just use the JAR
                    path = path.substring(0, path.indexOf("!/"));
                }
                classpathEntries.add(path);
            }
        }

        return String.join(File.pathSeparator, classpathEntries);
    }

    /**
     * Get the test classpath specifically (test classes and test dependencies).
     */
    @NotNull
    public String getTestClasspath() {
        StringBuilder classpath = new StringBuilder();

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

            // Add test output paths
            VirtualFile[] testSourceRoots = rootManager.getSourceRoots(true);
            for (VirtualFile root : testSourceRoots) {
                // Look for compiled test classes
                VirtualFile moduleRoot = ProjectRootManager.getInstance(project).getFileIndex()
                    .getContentRootForFile(root);
                if (moduleRoot != null) {
                    // Maven: target/test-classes
                    VirtualFile testClasses = moduleRoot.findFileByRelativePath("target/test-classes");
                    if (testClasses != null && testClasses.exists()) {
                        if (classpath.length() > 0) {
                            classpath.append(File.pathSeparator);
                        }
                        classpath.append(testClasses.getPath());
                    }
                    // Gradle: build/classes/java/test
                    VirtualFile gradleTestClasses = moduleRoot.findFileByRelativePath("build/classes/java/test");
                    if (gradleTestClasses != null && gradleTestClasses.exists()) {
                        if (classpath.length() > 0) {
                            classpath.append(File.pathSeparator);
                        }
                        classpath.append(gradleTestClasses.getPath());
                    }
                }
            }

            // Add test dependencies
            OrderEnumerator enumerator = rootManager.orderEntries()
                .withoutSdk()
                .recursively();

            for (VirtualFile root : enumerator.getClassesRoots()) {
                if (classpath.length() > 0) {
                    classpath.append(File.pathSeparator);
                }
                classpath.append(root.getPath());
            }
        }

        return classpath.toString();
    }

    /**
     * Find the workspace root (project base directory).
     */
    @Nullable
    public VirtualFile getWorkspaceRoot() {
        String basePath = project.getBasePath();
        if (basePath != null) {
            return LocalFileSystem.getInstance().findFileByPath(basePath);
        }
        return null;
    }

    private void detectKarateProject() {
        initialized = true;

        // All file index operations must be wrapped in a read action
        ReadAction.run(() -> {
            // Check for Karate in build files
            boolean foundInMaven = checkMavenForKarate();
            boolean foundInGradle = checkGradleForKarate();

            // Detect karate-config.js files
            detectKarateConfigFiles();

            // Detect feature files
            detectFeatureFiles();

            // Set project status
            isKarateProject = foundInMaven || foundInGradle || !featureFiles.isEmpty();

            if (isKarateProject) {
                LOG.info("Karate project detected: type=" + projectType +
                    ", version=" + karateVersion +
                    ", configs=" + karateConfigFiles.size() +
                    ", features=" + featureFiles.size() +
                    ", environments=" + detectedEnvironments);
            }
        });
    }

    private boolean checkMavenForKarate() {
        Collection<VirtualFile> pomFiles = FilenameIndex.getVirtualFilesByName(
            "pom.xml", GlobalSearchScope.projectScope(project));

        for (VirtualFile pomFile : pomFiles) {
            try {
                String content = new String(pomFile.contentsToByteArray());
                if (containsKarateDependency(content)) {
                    projectType = "maven";
                    extractKarateVersion(content);
                    return true;
                }
            } catch (Exception e) {
                LOG.warn("Error reading pom.xml: " + pomFile.getPath(), e);
            }
        }
        return false;
    }

    private boolean checkGradleForKarate() {
        Collection<VirtualFile> gradleFiles = new ArrayList<>(
            FilenameIndex.getVirtualFilesByName("build.gradle", GlobalSearchScope.projectScope(project)));
        gradleFiles.addAll(
            FilenameIndex.getVirtualFilesByName("build.gradle.kts", GlobalSearchScope.projectScope(project)));

        for (VirtualFile gradleFile : gradleFiles) {
            try {
                String content = new String(gradleFile.contentsToByteArray());
                if (containsKarateDependency(content)) {
                    projectType = "gradle";
                    extractKarateVersion(content);
                    return true;
                }
            } catch (Exception e) {
                LOG.warn("Error reading gradle file: " + gradleFile.getPath(), e);
            }
        }
        return false;
    }

    private boolean containsKarateDependency(String content) {
        return content.contains("karate-core") ||
               content.contains("karate-junit5") ||
               content.contains("karate-junit4") ||
               content.contains("karate-apache") ||
               content.contains("com.intuit.karate");
    }

    private void extractKarateVersion(String content) {
        // Try to find version in Maven format: <version>1.4.0</version> near karate
        Pattern mavenPattern = Pattern.compile(
            "<artifactId>karate-[^<]+</artifactId>\\s*<version>([^<]+)</version>");
        Matcher mavenMatcher = mavenPattern.matcher(content);
        if (mavenMatcher.find()) {
            String version = mavenMatcher.group(1);
            // Resolve Maven property references like ${karate.version}
            karateVersion = resolveMavenProperty(content, version);
            return;
        }

        // Try Gradle format: 'com.intuit.karate:karate-core:1.4.0' or 'io.karatelabs:karate-core:1.5.0'
        Pattern gradlePattern = Pattern.compile(
            "['\"](?:com\\.intuit\\.karate|io\\.karatelabs):karate-[^:]+:([^'\"]+)['\"]");
        Matcher gradleMatcher = gradlePattern.matcher(content);
        if (gradleMatcher.find()) {
            karateVersion = gradleMatcher.group(1);
        }
    }

    /**
     * Resolves Maven property references like ${karate.version} to actual values.
     */
    private String resolveMavenProperty(String content, String version) {
        if (!version.startsWith("${") || !version.endsWith("}")) {
            return version;
        }

        // Extract property name from ${property.name}
        String propertyName = version.substring(2, version.length() - 1);

        // Look for <property.name>value</property.name> in properties section
        // Handle dots in property names by escaping them for regex
        String escapedName = propertyName.replace(".", "\\.");
        Pattern propertyPattern = Pattern.compile("<" + escapedName + ">([^<]+)</" + escapedName + ">");
        Matcher propertyMatcher = propertyPattern.matcher(content);

        if (propertyMatcher.find()) {
            return propertyMatcher.group(1);
        }

        // Property not found, return original (will show as unresolved)
        return version;
    }

    private void detectKarateConfigFiles() {
        karateConfigFiles.clear();
        detectedEnvironments.clear();

        // Find karate-config.js files
        Collection<VirtualFile> configFiles = FilenameIndex.getVirtualFilesByName(
            "karate-config.js", GlobalSearchScope.projectScope(project));
        karateConfigFiles.addAll(configFiles);

        // Parse each config file for environments
        for (VirtualFile configFile : configFiles) {
            parseEnvironmentsFromConfig(configFile);
        }

        // Always add default environment
        detectedEnvironments.add("default");
    }

    private void parseEnvironmentsFromConfig(VirtualFile configFile) {
        try {
            String content = new String(configFile.contentsToByteArray());

            // Look for common patterns like: if (env == 'dev')
            Pattern envPattern = Pattern.compile("env\\s*==\\s*['\"]([^'\"]+)['\"]");
            Matcher matcher = envPattern.matcher(content);
            while (matcher.find()) {
                detectedEnvironments.add(matcher.group(1));
            }

            // Also look for: case 'dev':
            Pattern casePattern = Pattern.compile("case\\s+['\"]([^'\"]+)['\"]\\s*:");
            Matcher caseMatcher = casePattern.matcher(content);
            while (caseMatcher.find()) {
                detectedEnvironments.add(caseMatcher.group(1));
            }

        } catch (Exception e) {
            LOG.warn("Error parsing karate-config.js: " + configFile.getPath(), e);
        }
    }

    private void detectFeatureFiles() {
        featureFiles.clear();
        featureFiles.addAll(FilenameIndex.getAllFilesByExt(
            project, "feature", GlobalSearchScope.projectScope(project)));
    }

    @NotNull
    public Project getProject() {
        return project;
    }
}

