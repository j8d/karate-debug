package com.j8d.karate.intellij.project;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Service for detecting and managing Karate projects.
 * Checks for Maven/Gradle projects with Karate dependencies.
 */
@Service(Service.Level.PROJECT)
public final class KarateProjectService {
    
    private final Project project;
    private boolean isKarateProject = false;
    private boolean initialized = false;
    
    public KarateProjectService(@NotNull Project project) {
        this.project = project;
    }
    
    public static KarateProjectService getInstance(@NotNull Project project) {
        return project.getService(KarateProjectService.class);
    }
    
    /**
     * Check if this project contains Karate dependencies.
     */
    public boolean isKarateProject() {
        if (!initialized) {
            detectKarateProject();
        }
        return isKarateProject;
    }
    
    /**
     * Force re-detection of Karate project status.
     */
    public void refresh() {
        initialized = false;
        detectKarateProject();
    }
    
    private void detectKarateProject() {
        initialized = true;
        isKarateProject = checkMavenForKarate() || checkGradleForKarate() || hasFeatureFiles();
    }
    
    private boolean checkMavenForKarate() {
        Collection<VirtualFile> pomFiles = FilenameIndex.getVirtualFilesByName(
            "pom.xml", GlobalSearchScope.projectScope(project));
        
        for (VirtualFile pomFile : pomFiles) {
            try {
                String content = new String(pomFile.contentsToByteArray());
                if (content.contains("karate-core") || 
                    content.contains("karate-junit5") ||
                    content.contains("karate-junit4") ||
                    content.contains("karate-apache")) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore read errors
            }
        }
        return false;
    }
    
    private boolean checkGradleForKarate() {
        Collection<VirtualFile> gradleFiles = FilenameIndex.getVirtualFilesByName(
            "build.gradle", GlobalSearchScope.projectScope(project));
        gradleFiles.addAll(FilenameIndex.getVirtualFilesByName(
            "build.gradle.kts", GlobalSearchScope.projectScope(project)));
        
        for (VirtualFile gradleFile : gradleFiles) {
            try {
                String content = new String(gradleFile.contentsToByteArray());
                if (content.contains("karate-core") || 
                    content.contains("karate-junit5") ||
                    content.contains("com.intuit.karate")) {
                    return true;
                }
            } catch (Exception e) {
                // Ignore read errors
            }
        }
        return false;
    }
    
    private boolean hasFeatureFiles() {
        Collection<VirtualFile> featureFiles = FilenameIndex.getAllFilesByExt(
            project, "feature", GlobalSearchScope.projectScope(project));
        return !featureFiles.isEmpty();
    }
    
    @NotNull
    public Project getProject() {
        return project;
    }
}

