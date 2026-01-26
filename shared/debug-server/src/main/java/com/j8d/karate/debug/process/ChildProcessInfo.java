package com.j8d.karate.debug.process;

/**
 * Information about a running child process, including discovered ports.
 * 
 * This is populated after the child sends its "ready" event with port information.
 */
public class ChildProcessInfo {
    
    private final int ipcPort;
    private final int jdwpPort;
    private final int cdpPort;
    private final String cdpWebSocketUrl;
    private final String graalVmVersion;
    
    public ChildProcessInfo(int ipcPort, int jdwpPort, int cdpPort, 
                           String cdpWebSocketUrl, String graalVmVersion) {
        this.ipcPort = ipcPort;
        this.jdwpPort = jdwpPort;
        this.cdpPort = cdpPort;
        this.cdpWebSocketUrl = cdpWebSocketUrl;
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
     * The Chrome DevTools Protocol port for JS debugging (0 if not enabled).
     */
    public int getCdpPort() {
        return cdpPort;
    }
    
    /**
     * The WebSocket URL for CDP connection (null if not enabled).
     */
    public String getCdpWebSocketUrl() {
        return cdpWebSocketUrl;
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
        return cdpPort > 0 && cdpWebSocketUrl != null;
    }
    
    @Override
    public String toString() {
        return "ChildProcessInfo{" +
               "ipcPort=" + ipcPort +
               ", jdwpPort=" + jdwpPort +
               ", cdpPort=" + cdpPort +
               ", cdpWebSocketUrl='" + cdpWebSocketUrl + '\'' +
               ", graalVmVersion='" + graalVmVersion + '\'' +
               '}';
    }
}

