package com.j8d.karate.intellij.debug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Breakpoint type for Karate feature files.
 * Allows setting breakpoints on Gherkin steps.
 */
public class KarateBreakpointType extends XLineBreakpointType<KarateBreakpointProperties> {
    
    public static final String ID = "karate-line";
    
    public KarateBreakpointType() {
        super(ID, "Karate Breakpoints");
    }
    
    @Override
    public @Nullable KarateBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file,
                                                                            int line) {
        return new KarateBreakpointProperties();
    }
    
    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        // Allow breakpoints in .feature files
        String extension = file.getExtension();
        return "feature".equals(extension);
    }
}

