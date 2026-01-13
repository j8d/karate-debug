package com.j8d.karate.intellij.debug;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;

/**
 * Handler for Karate breakpoints.
 * Manages adding/removing breakpoints in the DAP session.
 */
public class KarateBreakpointHandler extends XBreakpointHandler<XLineBreakpoint<KarateBreakpointProperties>> {

    private final KarateDebugProcess debugProcess;

    public KarateBreakpointHandler(KarateDebugProcess debugProcess) {
        super(KarateBreakpointType.class);
        this.debugProcess = debugProcess;
    }

    @Override
    public void registerBreakpoint(@NotNull XLineBreakpoint<KarateBreakpointProperties> breakpoint) {
        XSourcePosition position = breakpoint.getSourcePosition();
        if (position == null) {
            return;
        }

        VirtualFile file = position.getFile();
        String filePath = file.getPath();
        int line = breakpoint.getLine() + 1; // DAP uses 1-based lines

        debugProcess.getDapClient().setBreakpoint(filePath, line);
    }

    @Override
    public void unregisterBreakpoint(@NotNull XLineBreakpoint<KarateBreakpointProperties> breakpoint,
                                      boolean temporary) {
        XSourcePosition position = breakpoint.getSourcePosition();
        if (position == null) {
            return;
        }

        VirtualFile file = position.getFile();
        String filePath = file.getPath();
        int line = breakpoint.getLine() + 1;

        debugProcess.getDapClient().removeBreakpoint(filePath, line);
    }
}

