package com.j8d.karate.intellij.project;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Listens for changes to build files (pom.xml, build.gradle) and feature files
 * to trigger project re-detection when dependencies change.
 */
@Service(Service.Level.PROJECT)
public final class KarateBuildFileListener implements Disposable {
    
    private static final Logger LOG = Logger.getInstance(KarateBuildFileListener.class);
    
    private static final Set<String> BUILD_FILES = Set.of(
        "pom.xml",
        "build.gradle",
        "build.gradle.kts"
    );
    
    private static final Set<String> CONFIG_FILES = Set.of(
        "karate-config.js"
    );
    
    private final Project project;
    
    public KarateBuildFileListener(@NotNull Project project) {
        this.project = project;
        
        // Subscribe to file system events
        project.getMessageBus().connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    handleFileEvents(events);
                }
            }
        );
    }
    
    public static KarateBuildFileListener getInstance(@NotNull Project project) {
        return project.getService(KarateBuildFileListener.class);
    }
    
    private void handleFileEvents(List<? extends VFileEvent> events) {
        boolean needsRefresh = false;
        
        for (VFileEvent event : events) {
            VirtualFile file = event.getFile();
            if (file == null) continue;
            
            String fileName = file.getName();
            
            // Check for build file changes
            if (BUILD_FILES.contains(fileName)) {
                LOG.info("Build file changed: " + file.getPath());
                needsRefresh = true;
                break;
            }
            
            // Check for config file changes
            if (CONFIG_FILES.contains(fileName)) {
                LOG.info("Karate config file changed: " + file.getPath());
                needsRefresh = true;
                break;
            }
            
            // Check for new/deleted feature files
            if (fileName.endsWith(".feature")) {
                LOG.info("Feature file changed: " + file.getPath());
                needsRefresh = true;
                break;
            }
        }
        
        if (needsRefresh) {
            refreshProjectDetection();
        }
    }
    
    private void refreshProjectDetection() {
        KarateProjectService.getInstance(project).refresh();
    }
    
    @Override
    public void dispose() {
        // Connection will be automatically disposed with the project
    }
}

