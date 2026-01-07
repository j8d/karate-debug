package com.j8d.karate.intellij.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.Collection;

/**
 * Content panel for the Karate tool window.
 * Displays a tree of feature files and scenarios.
 */
public class KarateToolWindowContent {
    
    private final JPanel panel;
    private final Tree tree;
    private final Project project;
    
    public KarateToolWindowContent(Project project) {
        this.project = project;
        
        panel = new JPanel(new BorderLayout());
        
        // Create tree
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Karate Features");
        tree = new Tree(new DefaultTreeModel(root));
        
        // Populate tree with feature files
        populateTree(root);
        
        // Add refresh button
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refresh());
        
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(refreshButton);
        
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JBScrollPane(tree), BorderLayout.CENTER);
    }
    
    private void populateTree(DefaultMutableTreeNode root) {
        root.removeAllChildren();
        
        Collection<VirtualFile> featureFiles = FilenameIndex.getAllFilesByExt(
            project, "feature", GlobalSearchScope.projectScope(project));
        
        for (VirtualFile file : featureFiles) {
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file.getName());
            fileNode.setUserObject(new FeatureFileNode(file));
            root.add(fileNode);
            
            // TODO: Parse file and add scenario nodes
        }
        
        ((DefaultTreeModel) tree.getModel()).reload();
    }
    
    public void refresh() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        populateTree(root);
    }
    
    public JPanel getPanel() {
        return panel;
    }
    
    /**
     * Node representing a feature file in the tree.
     */
    private static class FeatureFileNode {
        private final VirtualFile file;
        
        public FeatureFileNode(VirtualFile file) {
            this.file = file;
        }
        
        public VirtualFile getFile() {
            return file;
        }
        
        @Override
        public String toString() {
            return file.getName();
        }
    }
}

