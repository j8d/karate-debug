package com.j8d.karate.intellij.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.icons.AllIcons;
import com.intellij.xdebugger.frame.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Represents a variable group (scope) in the debugger variables view.
 */
public class KarateVariableGroup extends XValueGroup {
    
    private final KarateDebugProcess debugProcess;
    private final int variablesReference;
    
    public KarateVariableGroup(KarateDebugProcess debugProcess, String name, int variablesReference) {
        super(name);
        this.debugProcess = debugProcess;
        this.variablesReference = variablesReference;
    }
    
    @Override
    public @Nullable Icon getIcon() {
        return AllIcons.Debugger.Value;
    }
    
    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (variablesReference <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }

        debugProcess.getDapClient().getVariables(variablesReference).thenAccept(response -> {
            XValueChildrenList children = new XValueChildrenList();

            if (response != null && response.has("variables")) {
                JsonArray variables = response.getAsJsonArray("variables");
                for (int i = 0; i < variables.size(); i++) {
                    JsonObject variable = variables.get(i).getAsJsonObject();
                    children.add(new KarateVariable(debugProcess, variable, variablesReference));
                }
            }

            node.addChildren(children, true);
        }).exceptionally(e -> {
            node.setErrorMessage("Error loading variables: " + e.getMessage());
            return null;
        });
    }
}

/**
 * Represents a single variable in the debugger variables view.
 * Supports editing (hot-swap) via XValueModifier.
 */
class KarateVariable extends XNamedValue {

    private final KarateDebugProcess debugProcess;
    private final int parentVariablesReference;
    private String value;
    private String type;
    private final int variablesReference;

    public KarateVariable(KarateDebugProcess debugProcess, JsonObject variable, int parentVariablesReference) {
        super(variable.get("name").getAsString());
        this.debugProcess = debugProcess;
        this.parentVariablesReference = parentVariablesReference;
        this.value = variable.has("value") ? variable.get("value").getAsString() : "";
        this.type = variable.has("type") ? variable.get("type").getAsString() : null;
        this.variablesReference = variable.has("variablesReference")
            ? variable.get("variablesReference").getAsInt() : 0;
    }

    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        Icon icon = determineIcon();

        // Truncate long values for display
        String displayValue = value;
        if (displayValue.length() > 100) {
            displayValue = displayValue.substring(0, 97) + "...";
        }

        node.setPresentation(icon, type, displayValue, variablesReference > 0);
    }

    @Override
    public @Nullable XValueModifier getModifier() {
        // Allow editing for primitive types only (not objects/arrays with children)
        if (variablesReference > 0) {
            return null; // Cannot edit objects with children directly
        }
        return new KarateVariableModifier();
    }

    private Icon determineIcon() {
        if (type == null) {
            return AllIcons.Debugger.Value;
        }

        switch (type.toLowerCase()) {
            case "string":
                return AllIcons.Debugger.Db_primitive;
            case "number":
            case "int":
            case "integer":
            case "double":
            case "float":
                return AllIcons.Debugger.Db_primitive;
            case "boolean":
                return AllIcons.Debugger.Db_primitive;
            case "array":
            case "list":
                return AllIcons.Debugger.Db_array;
            case "object":
            case "map":
                return AllIcons.Debugger.Value;
            default:
                return AllIcons.Debugger.Value;
        }
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (variablesReference <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }

        debugProcess.getDapClient().getVariables(variablesReference).thenAccept(response -> {
            XValueChildrenList children = new XValueChildrenList();

            if (response != null && response.has("variables")) {
                JsonArray variables = response.getAsJsonArray("variables");
                for (int i = 0; i < variables.size(); i++) {
                    JsonObject variable = variables.get(i).getAsJsonObject();
                    children.add(new KarateVariable(debugProcess, variable, variablesReference));
                }
            }

            node.addChildren(children, true);
        }).exceptionally(e -> {
            node.setErrorMessage("Error loading variables: " + e.getMessage());
            return null;
        });
    }

    /**
     * XValueModifier implementation for editing variable values.
     */
    private class KarateVariableModifier extends XValueModifier {

        @Override
        public void setValue(@NotNull String newValue, @NotNull XModificationCallback callback) {
            debugProcess.getDapClient()
                .setVariable(parentVariablesReference, getName(), newValue)
                .thenAccept(response -> {
                    if (response != null && response.has("value")) {
                        // Update the local value
                        value = response.get("value").getAsString();
                        if (response.has("type")) {
                            type = response.get("type").getAsString();
                        }
                        callback.valueModified();
                    } else {
                        callback.errorOccurred("Failed to set variable value");
                    }
                })
                .exceptionally(e -> {
                    callback.errorOccurred(e.getMessage());
                    return null;
                });
        }

        @Override
        public @Nullable String getInitialValueEditorText() {
            return value;
        }
    }
}

