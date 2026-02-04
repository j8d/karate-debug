package com.j8d.karate.debug.process;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for spawning the child Karate runner process.
 */
public class ChildProcessConfig {
    
    private File workingDirectory;
    private String featurePath;
    private String classpath;
    private String javaPath = "java";
    private List<String> jvmArgs = new ArrayList<>();
    private String karateEnv;
    private String logLevel = "INFO";
    
    // Debug agent configuration
    private boolean enableJavaDebugging = false;
    private int jdwpPort = 0;  // 0 = auto-assign
    
    private boolean enableJsDebugging = false;
    private int jsDebugPort = 0;  // 0 = auto-assign

    // Step filtering configuration
    private boolean skipJdkClasses = true;           // Skip java.*, javax.*, jdk.*, sun.*, com.sun.*
    private boolean skipKarateFramework = true;      // Skip com.intuit.karate.*
    private boolean skipKarateDependencies = true;   // Skip com.jayway.jsonpath.*, io.netty.*, etc.

    // Additional source paths for inline variable display (semicolon-separated)
    private String sourcePaths;

    // ========== Builder-style setters ==========
    
    public ChildProcessConfig workingDirectory(File dir) {
        this.workingDirectory = dir;
        return this;
    }
    
    public ChildProcessConfig featurePath(String path) {
        this.featurePath = path;
        return this;
    }
    
    public ChildProcessConfig classpath(String classpath) {
        this.classpath = classpath;
        return this;
    }
    
    public ChildProcessConfig javaPath(String path) {
        this.javaPath = path;
        return this;
    }
    
    public ChildProcessConfig addJvmArg(String arg) {
        this.jvmArgs.add(arg);
        return this;
    }
    
    public ChildProcessConfig jvmArgs(List<String> args) {
        this.jvmArgs = new ArrayList<>(args);
        return this;
    }
    
    public ChildProcessConfig karateEnv(String env) {
        this.karateEnv = env;
        return this;
    }
    
    public ChildProcessConfig logLevel(String level) {
        this.logLevel = level;
        return this;
    }
    
    public ChildProcessConfig enableJavaDebugging(boolean enable) {
        this.enableJavaDebugging = enable;
        return this;
    }
    
    public ChildProcessConfig jdwpPort(int port) {
        this.jdwpPort = port;
        return this;
    }
    
    public ChildProcessConfig enableJsDebugging(boolean enable) {
        this.enableJsDebugging = enable;
        return this;
    }
    
    public ChildProcessConfig jsDebugPort(int port) {
        this.jsDebugPort = port;
        return this;
    }

    public ChildProcessConfig skipJdkClasses(boolean skip) {
        this.skipJdkClasses = skip;
        return this;
    }

    public ChildProcessConfig skipKarateFramework(boolean skip) {
        this.skipKarateFramework = skip;
        return this;
    }

    public ChildProcessConfig skipKarateDependencies(boolean skip) {
        this.skipKarateDependencies = skip;
        return this;
    }

    public ChildProcessConfig sourcePaths(String paths) {
        this.sourcePaths = paths;
        return this;
    }

    // ========== Getters ==========
    
    public File getWorkingDirectory() {
        return workingDirectory;
    }
    
    public String getFeaturePath() {
        return featurePath;
    }
    
    public String getClasspath() {
        return classpath;
    }
    
    public String getJavaPath() {
        return javaPath;
    }
    
    public List<String> getJvmArgs() {
        return jvmArgs;
    }
    
    public String getKarateEnv() {
        return karateEnv;
    }
    
    public String getLogLevel() {
        return logLevel;
    }
    
    public boolean isJavaDebuggingEnabled() {
        return enableJavaDebugging;
    }
    
    public int getJdwpPort() {
        return jdwpPort;
    }
    
    public boolean isJsDebuggingEnabled() {
        return enableJsDebugging;
    }
    
    public int getJsDebugPort() {
        return jsDebugPort;
    }

    public boolean isSkipJdkClasses() {
        return skipJdkClasses;
    }

    public boolean isSkipKarateFramework() {
        return skipKarateFramework;
    }

    public boolean isSkipKarateDependencies() {
        return skipKarateDependencies;
    }

    public String getSourcePaths() {
        return sourcePaths;
    }
}

