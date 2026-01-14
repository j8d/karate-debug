package com.j8d.karate.intellij.ui;

import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.j8d.karate.intellij.project.KarateProjectService;
import com.j8d.karate.intellij.project.KarateProjectSettings;
import com.j8d.karate.intellij.run.KarateConfigurationType;
import com.j8d.karate.intellij.run.KarateRunConfiguration;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content panel for the Karate tool window.
 * Displays a tree of feature files and scenarios with run/debug actions.
 */
public class KarateToolWindowContent {

    private static final Pattern SCENARIO_PATTERN = Pattern.compile(
        "^\\s*(Scenario|Scenario Outline):\\s*(.+)$"
    );
    private static final Pattern FEATURE_PATTERN = Pattern.compile(
        "^\\s*Feature:\\s*(.+)$"
    );

    private final JPanel panel;
    private final Tree tree;
    private final Project project;
    private final JLabel statusLabel;

    public KarateToolWindowContent(Project project) {
        this.project = project;

        panel = new JPanel(new BorderLayout());

        // Create tree with custom renderer
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Features");
        tree = new Tree(new DefaultTreeModel(root));
        tree.setCellRenderer(new KarateTreeCellRenderer());

        // Double-click to open file
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleDoubleClick();
                }
            }
        });

        // Right-click context menu
        tree.addMouseListener(new PopupHandler() {
            @Override
            public void invokePopup(Component comp, int x, int y) {
                showContextMenu(x, y);
            }
        });

        // Populate tree with feature files (in background to avoid EDT blocking)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Collect data in background
            List<FeatureFileNode> featureNodes = collectFeatureNodes();

            // Update UI on EDT
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                populateTreeFromNodes(root, featureNodes);
            });
        });

        // Toolbar with buttons
        JPanel toolbar = createToolbar();

        // Status bar
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JBScrollPane(tree), BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        updateStatus();

        // Listen for project detection completion to update UI
        // Note: This should NOT call refresh() as that would cause an infinite loop
        // (refresh triggers detection, detection notifies listeners, listener calls refresh...)
        KarateProjectService.getInstance(project).addDetectionListener(this::rebuildTree);
    }

    private JPanel createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));

        JButton refreshButton = new JButton(AllIcons.Actions.Refresh);
        refreshButton.setToolTipText("Refresh feature files");
        refreshButton.addActionListener(e -> refresh());

        JButton expandAllButton = new JButton(AllIcons.Actions.Expandall);
        expandAllButton.setToolTipText("Expand all");
        expandAllButton.addActionListener(e -> expandAll());

        JButton collapseAllButton = new JButton(AllIcons.Actions.Collapseall);
        collapseAllButton.setToolTipText("Collapse all");
        collapseAllButton.addActionListener(e -> collapseAll());

        JButton settingsButton = new JButton(AllIcons.General.Settings);
        settingsButton.setToolTipText("Karate Debug Settings");
        settingsButton.addActionListener(e -> openSettings());

        leftButtons.add(refreshButton);
        leftButtons.add(expandAllButton);
        leftButtons.add(collapseAllButton);
        rightButtons.add(settingsButton);

        toolbar.add(leftButtons, BorderLayout.WEST);
        toolbar.add(rightButtons, BorderLayout.EAST);

        return toolbar;
    }

    private void openSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, "Karate Debug");
    }

    private void handleDoubleClick() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        if (userObject instanceof FeatureFileNode) {
            openFile(((FeatureFileNode) userObject).getFile(), 0);
        } else if (userObject instanceof ScenarioNode) {
            ScenarioNode scenario = (ScenarioNode) userObject;
            openFile(scenario.getFile(), scenario.getLine());
        }
    }

    private void openFile(VirtualFile file, int line) {
        FileEditorManager.getInstance(project).openTextEditor(
            new OpenFileDescriptor(project, file, line, 0), true);
    }

    private void showContextMenu(int x, int y) {
        TreePath path = tree.getPathForLocation(x, y);
        if (path == null) return;

        tree.setSelectionPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        DefaultActionGroup group = new DefaultActionGroup();

        if (userObject instanceof FeatureFileNode || userObject instanceof ScenarioNode) {
            // Only show Debug option for consistency with VS Code extension
            group.add(new AnAction("Debug", "Debug this feature/scenario", AllIcons.Actions.StartDebugger) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    debugSelected();
                }
            });
            group.addSeparator();
        }

        if (userObject instanceof FeatureFileNode) {
            group.add(new AnAction("Open", "Open file", AllIcons.Actions.MenuOpen) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    openFile(((FeatureFileNode) userObject).getFile(), 0);
                }
            });
        }

        ActionPopupMenu popupMenu = ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.TOOLWINDOW_CONTENT, group);
        popupMenu.getComponent().show(tree, x, y);
    }

    private void debugSelected() {
        TreePath selectionPath = tree.getSelectionPath();
        if (selectionPath == null) {
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
        Object userObject = selectedNode.getUserObject();

        String featureFilePath = null;
        String scenarioName = null;
        int scenarioLine = 0; // 0 means run entire feature
        String configName = null;

        if (userObject instanceof FeatureFileNode) {
            FeatureFileNode featureNode = (FeatureFileNode) userObject;
            featureFilePath = featureNode.getFile().getPath();
            configName = "Karate: " + featureNode.getFile().getNameWithoutExtension();
        } else if (userObject instanceof ScenarioNode) {
            ScenarioNode scenarioNode = (ScenarioNode) userObject;
            scenarioName = scenarioNode.getName();
            // Convert from 0-based to 1-based line number
            scenarioLine = scenarioNode.getLine() + 1;
            configName = "Karate: " + scenarioName;

            // Get the parent feature file
            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) selectedNode.getParent();
            if (parentNode != null && parentNode.getUserObject() instanceof FeatureFileNode) {
                featureFilePath = ((FeatureFileNode) parentNode.getUserObject()).getFile().getPath();
            }
        }

        if (featureFilePath == null) {
            return;
        }

        // Create a temporary run configuration
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(
            configName,
            KarateConfigurationType.getInstance().getConfigurationFactories()[0]
        );

        KarateRunConfiguration configuration = (KarateRunConfiguration) settings.getConfiguration();
        configuration.setFeatureFile(featureFilePath);
        if (scenarioName != null) {
            configuration.setScenarioName(scenarioName);
        }
        configuration.setScenarioLine(scenarioLine);

        // Set as temporary and select it
        settings.setTemporary(true);
        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);

        // Execute with debug executor
        Executor executor = DefaultDebugExecutor.getDebugExecutorInstance();
        ProgramRunnerUtil.executeConfiguration(settings, executor);
    }

    /**
     * Collects feature nodes in background thread (does file I/O).
     */
    private List<FeatureFileNode> collectFeatureNodes() {
        List<FeatureFileNode> nodes = new ArrayList<>();

        KarateProjectService projectService = KarateProjectService.getInstance(project);
        // Make a defensive copy to avoid ConcurrentModificationException
        // since detection may be updating the list on another thread
        List<VirtualFile> featureFiles = new ArrayList<>(projectService.getFeatureFiles());

        for (VirtualFile file : featureFiles) {
            FeatureFileNode fileNode = new FeatureFileNode(file);

            // Parse feature file for scenarios (file I/O)
            List<ScenarioNode> scenarios = parseFeatureFile(file);
            if (!scenarios.isEmpty()) {
                fileNode.setFeatureName(scenarios.get(0).getFeatureName());
            }
            fileNode.setScenarios(scenarios);
            nodes.add(fileNode);
        }

        return nodes;
    }

    /**
     * Populates tree from pre-collected nodes (must run on EDT).
     */
    private void populateTreeFromNodes(DefaultMutableTreeNode root, List<FeatureFileNode> featureNodes) {
        root.removeAllChildren();

        for (FeatureFileNode fileNode : featureNodes) {
            DefaultMutableTreeNode fileTreeNode = new DefaultMutableTreeNode(fileNode);

            for (ScenarioNode scenario : fileNode.getScenarios()) {
                fileTreeNode.add(new DefaultMutableTreeNode(scenario));
            }

            root.add(fileTreeNode);
        }

        ((DefaultTreeModel) tree.getModel()).reload();
    }

    private List<ScenarioNode> parseFeatureFile(VirtualFile file) {
        List<ScenarioNode> scenarios = new ArrayList<>();
        String currentFeature = file.getNameWithoutExtension();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream()))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Check for Feature line
                Matcher featureMatcher = FEATURE_PATTERN.matcher(line);
                if (featureMatcher.find()) {
                    currentFeature = featureMatcher.group(1).trim();
                    continue;
                }

                // Check for Scenario/Scenario Outline
                Matcher scenarioMatcher = SCENARIO_PATTERN.matcher(line);
                if (scenarioMatcher.find()) {
                    String type = scenarioMatcher.group(1);
                    String name = scenarioMatcher.group(2).trim();
                    scenarios.add(new ScenarioNode(file, name, lineNumber - 1,
                        type.equals("Scenario Outline"), currentFeature));
                }
            }
        } catch (Exception e) {
            // Ignore parse errors
        }

        return scenarios;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void collapseAll() {
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            tree.collapseRow(i);
        }
    }

    /**
     * Full refresh: triggers project re-detection and rebuilds the tree.
     * Called by the refresh button.
     */
    public void refresh() {
        // Run refresh in background to avoid slow operations on EDT
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            KarateProjectService projectService = KarateProjectService.getInstance(project);
            projectService.refresh();
            // Note: rebuildTree() will be called by the detection listener
        });
    }

    /**
     * Rebuild the tree with cached data. Does NOT trigger detection.
     * Called by the detection listener when detection completes.
     */
    private void rebuildTree() {
        // Collect feature data in background (uses cached data from KarateProjectService)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<FeatureFileNode> featureNodes = collectFeatureNodes();

            // Update UI on EDT
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                populateTreeFromNodes(root, featureNodes);
                updateStatus();
            });
        });
    }

    private void updateStatus() {
        KarateProjectService projectService = KarateProjectService.getInstance(project);
        KarateProjectSettings settings = KarateProjectSettings.getInstance(project);

        StringBuilder status = new StringBuilder();
        if (projectService.isKarateProject()) {
            status.append(projectService.getFeatureFiles().size()).append(" features");
            String version = projectService.getKarateVersion();
            if (version != null) {
                status.append(" | Karate ").append(version);
            }
            status.append(" | env: ").append(settings.getEffectiveEnvironment());
        } else {
            status.append("Not a Karate project");
        }
        statusLabel.setText(status.toString());
    }

    public JPanel getPanel() {
        return panel;
    }

    /**
     * Custom tree cell renderer for Karate nodes.
     */
    private static class KarateTreeCellRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObject = node.getUserObject();

            if (userObject instanceof FeatureFileNode) {
                FeatureFileNode feature = (FeatureFileNode) userObject;
                setIcon(AllIcons.FileTypes.Any_type);
                append(feature.getDisplayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" - " + feature.getFile().getName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (userObject instanceof ScenarioNode) {
                ScenarioNode scenario = (ScenarioNode) userObject;
                // Use green run icons instead of test icons (which can appear red/warning)
                setIcon(scenario.isOutline() ? AllIcons.RunConfigurations.TestState.Run_run : AllIcons.RunConfigurations.TestState.Run);
                append(scenario.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" :" + (scenario.getLine() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else {
                append(value.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                setIcon(AllIcons.Nodes.Folder);
            }
        }
    }

    /**
     * Node representing a feature file in the tree.
     */
    private static class FeatureFileNode {
        private final VirtualFile file;
        private String featureName;
        private List<ScenarioNode> scenarios = new ArrayList<>();

        public FeatureFileNode(VirtualFile file) {
            this.file = file;
            this.featureName = file.getNameWithoutExtension();
        }

        public VirtualFile getFile() {
            return file;
        }

        public String getDisplayName() {
            return featureName;
        }

        public void setFeatureName(String name) {
            if (name != null && !name.isEmpty()) {
                this.featureName = name;
            }
        }

        public List<ScenarioNode> getScenarios() {
            return scenarios;
        }

        public void setScenarios(List<ScenarioNode> scenarios) {
            this.scenarios = scenarios;
        }

        @Override
        public String toString() {
            return featureName;
        }
    }

    /**
     * Node representing a scenario in the tree.
     */
    private static class ScenarioNode {
        private final VirtualFile file;
        private final String name;
        private final int line;
        private final boolean outline;
        private final String featureName;

        public ScenarioNode(VirtualFile file, String name, int line, boolean outline, String featureName) {
            this.file = file;
            this.name = name;
            this.line = line;
            this.outline = outline;
            this.featureName = featureName;
        }

        public VirtualFile getFile() {
            return file;
        }

        public String getName() {
            return name;
        }

        public int getLine() {
            return line;
        }

        public boolean isOutline() {
            return outline;
        }

        public String getFeatureName() {
            return featureName;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

