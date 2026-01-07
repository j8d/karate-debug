package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.impl.XSourcePositionImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single stack frame during debugging.
 * Maps DAP frame to IntelliJ's XStackFrame.
 */
public class KarateStackFrame extends XStackFrame {
    
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
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(sourcePath);
            if (file != null) {
                // DAP uses 1-based lines, IntelliJ uses 0-based
                this.sourcePosition = XSourcePositionImpl.create(file, line - 1);
            }
        }
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

