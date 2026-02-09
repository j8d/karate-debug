package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the execution stack during debugging.
 * Maps DAP stack frames to IntelliJ's XStackFrame.
 */
public class KarateExecutionStack extends XExecutionStack {
    
    private final KarateDebugProcess debugProcess;
    private final int threadId;
    private final List<KarateStackFrame> frames;
    
    public KarateExecutionStack(KarateDebugProcess debugProcess, int threadId, JsonObject stackTraceResponse) {
        super("Karate Thread");
        this.debugProcess = debugProcess;
        this.threadId = threadId;
        this.frames = parseFrames(stackTraceResponse);
    }
    
    private List<KarateStackFrame> parseFrames(JsonObject response) {
        List<KarateStackFrame> result = new ArrayList<>();
        
        if (response != null && response.has("stackFrames")) {
            JsonArray stackFrames = response.getAsJsonArray("stackFrames");
            for (int i = 0; i < stackFrames.size(); i++) {
                JsonObject frame = stackFrames.get(i).getAsJsonObject();
                result.add(new KarateStackFrame(debugProcess, frame));
            }
        }
        
        return result;
    }
    
    @Override
    public @Nullable XStackFrame getTopFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }
    
    @Override
    public void computeStackFrames(int firstFrameIndex, XStackFrameContainer container) {
        if (firstFrameIndex < frames.size()) {
            container.addStackFrames(frames.subList(firstFrameIndex, frames.size()), true);
        } else {
            container.addStackFrames(List.of(), true);
        }
    }
}

