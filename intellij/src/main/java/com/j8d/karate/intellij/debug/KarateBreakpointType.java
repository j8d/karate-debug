package com.j8d.karate.intellij.debug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Breakpoint type for Karate feature files.
 * Allows setting breakpoints on Gherkin steps.
 * Supports conditional breakpoints via condition expressions.
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

    @Override
    public @Nullable XDebuggerEditorsProvider getEditorsProvider(@NotNull XLineBreakpoint<KarateBreakpointProperties> breakpoint,
                                                                  @NotNull Project project) {
        // Return our editors provider to enable the condition field in the breakpoint dialog
        return new KarateEditorsProvider();
    }
}

