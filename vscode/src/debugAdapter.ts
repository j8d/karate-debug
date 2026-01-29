import { ChildProcess, spawn, execSync } from 'child_process';
import * as fs from 'fs';
import * as net from 'net';
import * as path from 'path';
import * as vscode from 'vscode';
import { GraalInspectorClient } from './graalInspector';

export class KarateDebugAdapterFactory implements vscode.DebugAdapterDescriptorFactory {
    private readonly context: vscode.ExtensionContext;
    private readonly outputChannel: vscode.OutputChannel;
    private serverProcess: ChildProcess | null = null;
    private lastJsDebugPort: number = 0;
    private graalInspector: GraalInspectorClient | null = null;

    constructor(context: vscode.ExtensionContext, outputChannel: vscode.OutputChannel) {
        this.context = context;
        this.outputChannel = outputChannel;
    }

    private log(message: string): void {
        this.outputChannel.appendLine(`[Karate Debug] ${message}`);
    }

    /** Log raw output without prefix (for stdout from debug server) */
    private logRaw(message: string): void {
        // Check if line should be filtered out
        if (this.shouldFilterLog(message)) {
            return;
        }
        this.outputChannel.appendLine(message);
    }

    /** Check if a log line should be filtered out based on user settings (case-insensitive) */
    private shouldFilterLog(message: string): boolean {
        const config = vscode.workspace.getConfiguration('karateDebug');
        const excludePatterns = config.get<string[]>('logFilter.exclude', []);

        if (excludePatterns.length === 0) {
            return false;
        }

        const lowerMessage = message.toLowerCase();
        return excludePatterns.some(pattern => lowerMessage.includes(pattern.toLowerCase()));
    }

    async createDebugAdapterDescriptor(
        session: vscode.DebugSession,
        _executable: vscode.DebugAdapterExecutable | undefined
    ): Promise<vscode.DebugAdapterDescriptor | null> {
        const config = session.configuration;
        const workspaceFolder = session.workspaceFolder;

        if (!workspaceFolder) {
            throw new Error('No workspace folder found');
        }

        // Clean up any previous session before starting a new one
        this.cleanupPreviousSession();

        // Find a free port for the debug server
        const port = await this.findFreePort();

        // Start our custom Karate debug server
        const javaDebugPort = config.javaDebugPort || 0;
        const jsDebugPort = config.jsDebugPort || 0;
        const enablePolyglotDebugging = config.enablePolyglotDebugging || false;
        const enableJavaDebugging = config.enableJavaDebugging || false;
        const enableJsDebugging = config.enableJsDebugging || false;

        // Track the JS debug port so we can clean it up on next session
        this.lastJsDebugPort = jsDebugPort;

        // Also kill any existing process on the JS debug port before starting
        if (jsDebugPort > 0) {
            this.killProcessOnPort(jsDebugPort);
        }

        this.serverProcess = await this.startDebugServer(
            workspaceFolder.uri.fsPath,
            port,
            config.karateEnv || 'dev',
            javaDebugPort,
            jsDebugPort,
            enablePolyglotDebugging,
            enableJavaDebugging,
            enableJsDebugging
        );

        // If Java debug port is specified, notify user
        if (javaDebugPort > 0) {
            this.log(`Java debug agent enabled on port ${javaDebugPort}`);
            this.log(`Attach Java debugger to port ${javaDebugPort} for Java breakpoints`);
        }

        if (jsDebugPort > 0) {
            this.log(`[Experimental] JavaScript inspector enabled on port ${jsDebugPort}`);
        }

        // Wait for debug server to be ready
        await this.waitForServer(port, 30000);

        // Connect to the GraalVM inspector asynchronously
        // The inspector only starts when Karate loads JavaScript, which happens
        // after the DAP server is ready. We connect in the background.
        if (jsDebugPort > 0) {
            this.graalInspector = new GraalInspectorClient(
                this.outputChannel,
                workspaceFolder.uri.fsPath
            );

            // Connect asynchronously - don't block the debug session
            // The inspector will be ready shortly after Karate starts executing
            this.connectInspectorWithRetry(jsDebugPort, 30, 500).catch(err => {
                this.log(`[GraalInspector] Failed to connect: ${err}`);
            });
        }

        // Show output channel AFTER connection (to avoid Debug Console taking focus)
        setTimeout(() => {
            this.outputChannel.show(true);  // true = preserveFocus
        }, 100);

        // Return a socket connection to the debug server
        return new vscode.DebugAdapterServer(port);
    }

    dispose(): void {
        this.cleanupPreviousSession();
    }

    /**
     * Clean up any previous debug session - kill server process and free ports
     */
    private cleanupPreviousSession(): void {
        // Disconnect the GraalVM inspector client
        if (this.graalInspector) {
            this.graalInspector.disconnect();
            this.graalInspector = null;
        }

        // Kill the tracked server process
        if (this.serverProcess) {
            this.serverProcess.kill();
            this.serverProcess = null;
        }

        // Kill any process using the last JS debug port (in case it didn't shut down cleanly)
        if (this.lastJsDebugPort > 0) {
            this.killProcessOnPort(this.lastJsDebugPort);
        }
    }

    /**
     * Kill any process listening on the specified port (macOS/Linux only)
     */
    private killProcessOnPort(port: number): void {
        try {
            // Use lsof to find and kill processes on the port
            execSync(`lsof -ti:${port} | xargs kill -9 2>/dev/null`, { stdio: 'ignore' });
        } catch {
            // Ignore errors - port may already be free
        }
    }

    /**
     * Connect to the GraalVM inspector with retries
     */
    private async connectInspectorWithRetry(port: number, maxRetries: number, delayMs: number): Promise<void> {
        for (let attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                await this.graalInspector!.connect(port);
                this.log('[GraalInspector] Connected successfully');
                return;
            } catch (error) {
                if (attempt < maxRetries) {
                    this.log(`[GraalInspector] Connection attempt ${attempt} failed, retrying in ${delayMs}ms...`);
                    await new Promise(resolve => setTimeout(resolve, delayMs));
                } else {
                    this.log(`[GraalInspector] Failed to connect after ${maxRetries} attempts: ${error}`);
                }
            }
        }
    }

    private async findFreePort(): Promise<number> {
        return new Promise((resolve, reject) => {
            const server = net.createServer();
            server.listen(0, () => {
                const address = server.address();
                if (address && typeof address === 'object') {
                    const port = address.port;
                    server.close(() => resolve(port));
                } else {
                    reject(new Error('Could not find free port'));
                }
            });
            server.on('error', reject);
        });
    }

    private async startDebugServer(
        workspaceRoot: string,
        port: number,
        karateEnv: string,
        javaDebugPort: number = 0,
        jsDebugPort: number = 0,
        enablePolyglotDebugging: boolean = false,
        enableJavaDebugging: boolean = false,
        enableJsDebugging: boolean = false
    ): Promise<ChildProcess> {
        const config = vscode.workspace.getConfiguration('karateRunner');

        // Find our custom debug server JAR
        const debugServerJar = this.findDebugServerJar();
        if (!debugServerJar) {
            throw new Error('Could not find karate-debug-server.jar in extension');
        }

        // Find Java - prefer Java 17 or 21 for GraalJS compatibility
        const javaPath = this.findCompatibleJava(config);

        // Build classpath using Maven to get all project dependencies
        // This ensures we use the same versions as the project (including GraalJS)
        const testClasses = path.join(workspaceRoot, 'target', 'test-classes');
        const mainClasses = path.join(workspaceRoot, 'target', 'classes');

        // Get Maven classpath
        const mavenCp = await this.getMavenClasspath(workspaceRoot);

        // Our debug server JAR should be FIRST, followed by project classes, then Maven deps
        const fullClasspath = `${debugServerJar}:${testClasses}:${mainClasses}:${mavenCp}`;

        // Build Java args - add debug agents if specified
        const args: string[] = [];

        // In polyglot mode, the child process handles debug agents, not the parent
        if (!enablePolyglotDebugging) {
            if (javaDebugPort > 0) {
                // Enable JDWP for Java debugging - suspend=n so Karate starts immediately
                args.push(`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${javaDebugPort}`);
            }

            if (jsDebugPort > 0) {
                // Enable GraalVM Chrome Inspector for JavaScript debugging
                // This allows Chrome DevTools to attach and debug embedded JS in Karate tests
                // Note: --inspect only works with GraalVM JDK, so we use polyglot system properties
                // which work with GraalJS running on any JDK
                args.push(`-Dpolyglot.inspect=${jsDebugPort}`);

                // Use Suspend=true so GraalVM waits for debugger before executing JS
                // This should give us time to set breakpoints before karate-config.js runs
                args.push('-Dpolyglot.inspect.Suspend=true');

                // WaitAttached ensures GraalVM waits for the debugger to fully attach
                args.push('-Dpolyglot.inspect.WaitAttached=true');

                // Set source path to user's test sources to help filter out internal Karate JS
                const sourcePath = path.join(workspaceRoot, 'src', 'test', 'java');
                args.push(`-Dpolyglot.inspect.SourcePath=${sourcePath}`);
            }
        }

        // Get log level from settings
        const logLevel = vscode.workspace.getConfiguration('karateDebug').get<string>('logLevel', 'info');

        args.push('-cp', fullClasspath);
        args.push('com.j8d.karate.debug.DebugServer');
        args.push('-p', String(port));
        args.push('-w', workspaceRoot);
        args.push('-e', karateEnv);
        args.push('-l', logLevel);

        // Add polyglot mode flags
        if (enablePolyglotDebugging) {
            args.push('--polyglot');
            args.push('--classpath', fullClasspath);
        }

        this.log(`Starting Karate debug server on port ${port}`);
        if (enablePolyglotDebugging) {
            this.log(`[Experimental] Polyglot debugging enabled`);
            this.log(`  - Java debugging: ${enableJavaDebugging ? 'enabled' : 'disabled'}`);
            this.log(`  - JavaScript debugging: ${enableJsDebugging ? 'enabled' : 'disabled'}`);
        } else {
            if (javaDebugPort > 0) {
                this.log(`Java debug agent listening on port ${javaDebugPort}`);
            }
            if (jsDebugPort > 0) {
                this.log(`[Experimental] JavaScript inspector listening on port ${jsDebugPort}`);
            }
        }
        this.log(`Java: ${javaPath}`);
        this.log(`Debug server JAR: ${debugServerJar}`);
        this.log(`Workspace: ${workspaceRoot}`);
        this.log(`Environment: ${karateEnv}`);
        this.log(`Log level: ${logLevel}`);
        this.log(`Classpath: (${fullClasspath.split(':').length} entries)`);

        const serverProcess = spawn(javaPath, args, {
            cwd: workspaceRoot,
            env: { ...process.env }
        });

        // Log stdout for debugging - raw output without prefix for clean JSON display
        serverProcess.stdout?.on('data', (data) => {
            const lines = data.toString().split('\n').filter((l: string) => l.trim());
            lines.forEach((line: string) => {
                this.logRaw(line);
            });
        });

        // Log stderr for errors
        serverProcess.stderr?.on('data', (data) => {
            this.log(`[stderr] ${data.toString().trim()}`);
        });

        serverProcess.on('error', (err) => {
            this.log(`ERROR: Failed to start debug server: ${err.message}`);
            vscode.window.showErrorMessage(`Failed to start debug server: ${err.message}`);
        });

        serverProcess.on('exit', (code) => {
            this.log(`Debug server exited with code ${code}`);
        });

        return serverProcess;
    }

    private findDebugServerJar(): string | null {
        // Look for the JAR in the extension's resources
        const extensionPath = this.context.extensionPath;
        const jarPath = path.join(extensionPath, 'resources', 'karate-debug-server-1.0.0.jar');

        if (fs.existsSync(jarPath)) {
            return jarPath;
        }

        // Also check in shared/debug-server/target for development (monorepo structure)
        const devJarPath = path.join(extensionPath, '..', 'shared', 'debug-server', 'target', 'karate-debug-server-1.0.0.jar');
        if (fs.existsSync(devJarPath)) {
            return devJarPath;
        }

        // Legacy path for backwards compatibility
        const legacyDevJarPath = path.join(extensionPath, 'debug-server', 'target', 'karate-debug-server-1.0.0.jar');
        if (fs.existsSync(legacyDevJarPath)) {
            return legacyDevJarPath;
        }

        this.log(`Could not find karate-debug-server.jar. Checked: ${jarPath}, ${devJarPath}, ${legacyDevJarPath}`);
        return null;
    }

    private async waitForServer(port: number, timeout: number): Promise<void> {
        const startTime = Date.now();
        this.log(`Waiting for debug server to be ready on port ${port}...`);

        while (Date.now() - startTime < timeout) {
            try {
                await this.tryConnect(port);
                this.log(`Debug server ready on port ${port}`);
                return;
            } catch {
                await this.sleep(500);
            }
        }

        this.log(`ERROR: Timeout waiting for debug server on port ${port}`);
        throw new Error(`Timeout waiting for debug server on port ${port}`);
    }

    private async tryConnect(port: number): Promise<void> {
        return new Promise((resolve, reject) => {
            const socket = new net.Socket();
            socket.setTimeout(1000);

            socket.on('connect', () => {
                socket.destroy();
                resolve();
            });

            socket.on('timeout', () => {
                socket.destroy();
                reject(new Error('Connection timeout'));
            });

            socket.on('error', (err) => {
                socket.destroy();
                reject(err);
            });

            socket.connect(port, '127.0.0.1');
        });
    }

    private sleep(ms: number): Promise<void> {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    private findCompatibleJava(config: vscode.WorkspaceConfiguration): string {
        // First check user configuration
        const configuredJavaHome = config.get<string>('javaHome', '');
        if (configuredJavaHome) {
            this.log(`Using configured Java: ${configuredJavaHome}`);
            return path.join(configuredJavaHome, 'bin', 'java');
        }

        // Look for compatible Java versions in SDKMAN first (17, 21 preferred for GraalJS)
        // This takes priority over JAVA_HOME because JAVA_HOME might point to Java 25+
        const sdkmanDir = path.join(process.env.HOME || '', '.sdkman', 'candidates', 'java');
        if (fs.existsSync(sdkmanDir)) {
            const versions = fs.readdirSync(sdkmanDir);
            // Prefer Java 21, then 17, avoid Java 25+
            for (const preferred of ['21', '17']) {
                for (const version of versions) {
                    if (version.startsWith(preferred) && version !== 'current') {
                        const javaPath = path.join(sdkmanDir, version, 'bin', 'java');
                        if (fs.existsSync(javaPath)) {
                            this.log(`Using SDKMAN Java ${version} for GraalJS compatibility`);
                            return javaPath;
                        }
                    }
                }
            }
        }

        // Fallback to JAVA_HOME
        if (process.env.JAVA_HOME) {
            const javaFromEnv = path.join(process.env.JAVA_HOME, 'bin', 'java');
            if (fs.existsSync(javaFromEnv)) {
                this.log(`Using JAVA_HOME: ${process.env.JAVA_HOME}`);
                return javaFromEnv;
            }
        }

        // Fallback to java on PATH
        this.log('Using java from PATH');
        return 'java';
    }

    private async getMavenClasspath(workspaceRoot: string): Promise<string> {
        return new Promise((resolve, _reject) => {
            const cpFile = path.join(workspaceRoot, 'target', 'debug-classpath.txt');
            const mvn = spawn('mvn', [
                '-q',
                'dependency:build-classpath',
                `-Dmdep.outputFile=${cpFile}`
            ], {
                cwd: workspaceRoot,
                shell: true
            });

            mvn.on('close', (code) => {
                if (code === 0) {
                    try {
                        const cp = fs.readFileSync(cpFile, 'utf-8').trim();
                        this.log(`Maven classpath loaded (${cp.split(':').length} entries)`);
                        resolve(cp);
                    } catch (err) {
                        this.log(`Warning: Could not read Maven classpath file: ${err}`);
                        resolve('');
                    }
                } else {
                    this.log(`Warning: Maven dependency:build-classpath failed with code ${code}`);
                    resolve('');
                }
            });

            mvn.on('error', (err) => {
                this.log(`Warning: Maven failed: ${err.message}`);
                resolve('');
            });

            // Timeout after 60 seconds
            setTimeout(() => {
                mvn.kill();
                resolve('');
            }, 60000);
        });
    }
}
