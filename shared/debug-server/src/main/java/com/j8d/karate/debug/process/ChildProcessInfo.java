package com.j8d.karate.debug.process;

/**
 * Information about a running child process, including discovered ports.
 *
 * This is populated after the child sends its "ready" event with port information.
 */
public class ChildProcessInfo {

    private final int ipcPort;
    private final int jdwpPort;
    private final int jsDapPort;  // GraalVM DAP server port for JavaScript debugging
    private final String graalVmVersion;

    public ChildProcessInfo(int ipcPort, int jdwpPort, int jsDapPort, String graalVmVersion) {
        this.ipcPort = ipcPort;
        this.jdwpPort = jdwpPort;
        this.jsDapPort = jsDapPort;
        this.graalVmVersion = graalVmVersion;
    }

    /**
     * The port the child's IPC server is listening on.
     */
    public int getIpcPort() {
        return ipcPort;
    }

    /**
     * The JDWP port for Java debugging (0 if not enabled).
     */
    public int getJdwpPort() {
        return jdwpPort;
    }

    /**
     * The GraalVM DAP server port for JavaScript debugging (0 if not enabled).
     */
    public int getJsDapPort() {
        return jsDapPort;
    }

    /**
     * The GraalVM version if available (null otherwise).
     */
    public String getGraalVmVersion() {
        return graalVmVersion;
    }

    /**
     * Returns true if Java debugging is available.
     */
    public boolean hasJavaDebugging() {
        return jdwpPort > 0;
    }

    /**
     * Returns true if JavaScript debugging is available.
     */
    public boolean hasJsDebugging() {
        return jsDapPort > 0;
    }

    @Override
    public String toString() {
        return "ChildProcessInfo{" +
               "ipcPort=" + ipcPort +
               ", jdwpPort=" + jdwpPort +
               ", jsDapPort=" + jsDapPort +
               ", graalVmVersion='" + graalVmVersion + '\'' +
               '}';
    }
}

