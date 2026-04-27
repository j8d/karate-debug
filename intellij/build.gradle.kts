import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.15.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Calculate untilBuild dynamically from platformVersion
// platformVersion "2025.1.1" -> untilBuild "263.*" (support +1 year, up to .3 release)
fun calculateUntilBuild(): String {
    val platformVersion = providers.gradleProperty("platformVersion").get()
    val year = platformVersion.substringBefore(".").toInt()
    val nextYear = year + 1
    val buildPrefix = "${nextYear % 100}3"  // e.g., 2026 -> "263" (last release of next year)
    return "$buildPrefix.*"
}

// Read CHANGELOG.md and convert to HTML for plugin changeNotes
fun parseChangelogToHtml(): String {
    val changelog = file("CHANGELOG.md").readText()
    val html = StringBuilder()

    // Simple markdown to HTML conversion for changelog
    changelog.lines().forEach { line ->
        when {
            line.startsWith("## [") -> {
                // Version header: ## [0.1.3] - 2026-01-16
                val version = line.substringAfter("[").substringBefore("]")
                html.appendLine("<h3>$version</h3>")
            }
            line.startsWith("### ") -> {
                // Section header: ### Fixed
                // Skip these - JetBrains format doesn't need sub-headers
            }
            line.startsWith("- ") -> {
                // List item
                val item = line.removePrefix("- ")
                html.appendLine("<li>$item</li>")
            }
            line.isBlank() && html.isNotEmpty() -> {
                // Add ul tags around list items
            }
        }
    }

    // Wrap list items in <ul> tags
    return html.toString()
        .replace(Regex("(<h3>.*?</h3>)\n(<li>)"), "$1\n<ul>\n$2")
        .replace(Regex("(</li>)\n(<h3>)"), "$1\n</ul>\n\n$2")
        .replace(Regex("(</li>)\n*$"), "$1\n</ul>")
        .trim()
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    // IntelliJ 2024.2+ requires Java 21
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    intellijPlatform {
        val platformType = providers.gradleProperty("platformType")
        val platformVersion = providers.gradleProperty("platformVersion")

        create(platformType, platformVersion)

        // Java plugin for project detection
        bundledPlugin("com.intellij.java")

        // Maven and Gradle plugins for project detection
        bundledPlugin("org.jetbrains.idea.maven")
        bundledPlugin("org.jetbrains.plugins.gradle")

        // TextMate bundles support for syntax highlighting
        bundledPlugin("org.jetbrains.plugins.textmate")

        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Gson for DAP JSON protocol
    implementation("com.google.code.gson:gson:2.14.0")

    // Add the shared debug-server JAR
    implementation(files("../shared/debug-server/target/karate-debug-server-1.0.0.jar"))

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "231"    // IntelliJ 2023.1 (minimum supported)
            untilBuild = calculateUntilBuild()  // Dynamically set to +1 year from platformVersion
        }

        description = """
            <p>A powerful debugger for <a href="https://github.com/karatelabs/karate">Karate</a> API tests.
            Set breakpoints directly in your <code>.feature</code> files, step through scenarios including
            Java and JavaScript code, inspect variables, and run tests with a single click.</p>

            <h3>Features</h3>
            <ul>
                <li><b>Breakpoint Debugging for Karate, Java, and JavaScript</b> - Debug Karate, Java, and JavaScript
                    code in the same session. Set breakpoints in <code>.feature</code>, <code>.java</code>, and
                    <code>.js</code> files. Step through your tests line by line.</li>
                <li><b>Hot Reload Variables</b> - Modify variable values on-the-fly while paused at a breakpoint.
                    Right-click any variable in the Variables panel and set a new value.</li>
                <li><b>Match Diagnostics</b> - See pass/fail highlights on match statements with actual values inline.
                    One-click fixes to update expected values from actual results.</li>
                <li><b>One-Click Test Execution</b> - Gutter icons appear next to every Feature and Scenario,
                    letting you debug with a single click.</li>
                <li><b>Feature Explorer</b> - Browse all your Karate features and scenarios in the Karate tool window.
                    Navigate your test suite at a glance and run any test directly.</li>
                <li><b>Environment Switching</b> - Quickly switch between environments (dev, qa, stage, or custom)
                    from the status bar. Your selection persists across sessions.</li>
                <li><b>Syntax Highlighting</b> - Full syntax highlighting for the Karate DSL, including Gherkin keywords,
                    JSON/XML payloads, JavaScript expressions, and embedded variables.</li>
                <li><b>File Navigation</b> - Clickable file references throughout your feature files. Ctrl+Click to
                    navigate to <code>classpath:</code> files, <code>read()</code> targets, and <code>@tag</code> references.</li>
                <li><b>Log Breakpoints</b> - Pause execution when specific strings appear in log output.
                    Useful for catching exceptions or error messages without setting traditional breakpoints.</li>
                <li><b>Log Filtering</b> - Hide noisy log output by configuring exclude patterns.
                    Filter out verbose framework messages to focus on what matters.</li>
                <li><b>Conditional Breakpoints</b> - Set conditions on breakpoints using JavaScript expressions.</li>
            </ul>

            <h3>Configuration</h3>
            <p>Configure the plugin via <b>Settings &gt; Tools &gt; Karate Debug</b>:</p>
            <ul>
                <li><b>Environments</b> - List of available Karate environments (comma-separated)</li>
                <li><b>Default Environment</b> - Environment used when starting a debug session</li>
                <li><b>Log Level</b> - Log level for Karate Debug output (error, warn, info, debug, trace)</li>
                <li><b>Match Diagnostics</b> - Show passing/failing highlights and actual values</li>
                <li><b>Log Filter</b> - Comma-separated strings to hide from log output</li>
                <li><b>Log Breakpoints</b> - Comma-separated strings that pause execution when found in logs</li>
            </ul>

            <h3>Step Filtering (Java Debugging)</h3>
            <p>Control which code is shown when stepping through Java code:</p>
            <ul>
                <li><b>Show JDK Classes</b> - Step into JDK core classes (java.*, javax.*, jdk.*, sun.*, com.sun.*)</li>
                <li><b>Show Karate Framework</b> - Step into Karate framework classes (com.intuit.karate.*)</li>
                <li><b>Show Karate Dependencies</b> - Step into Karate's third-party dependencies (jsonpath, netty, slf4j, etc.)</li>
            </ul>
            <p>When unchecked (default), stepping into framework code will automatically step out and return to user code.</p>

            <h3>Getting Started</h3>
            <ol>
                <li>Open a project containing Karate tests</li>
                <li>Open a <code>.feature</code> file</li>
                <li>Set breakpoints by clicking in the gutter</li>
                <li>Click the debug icon in the gutter or use the Karate tool window</li>
            </ol>

            <h3>Requirements</h3>
            <ul>
                <li>Java 17+ (Java 21 recommended)</li>
                <li>Maven or Gradle project with Karate dependencies</li>
            </ul>

            <h3>Early Access Program (EAP)</h3>
            <p>Want to try new features before they're released? Join our EAP channel:</p>
            <ol>
                <li>Go to <b>Settings &gt; Plugins &gt; &#9881; (gear icon) &gt; Manage Plugin Repositories</b></li>
                <li>Click <b>+</b> and add: <code>https://plugins.jetbrains.com/plugins/eap/list</code></li>
                <li>EAP versions will now appear in the plugin updates</li>
            </ol>
            <p>EAP versions may contain experimental features and are updated more frequently.</p>

            <h3>Resources</h3>
            <ul>
                <li><a href="https://karatedebug.com">Website</a> - Documentation and getting started guides</li>
                <li><a href="https://karatedebug.com/?contact=bug&amp;ide=intellij">Report a Bug</a></li>
                <li><a href="https://karatedebug.com/?contact=feature&amp;ide=intellij">Request a Feature</a></li>
                <li><a href="https://karatedebug.com/?contact=general&amp;ide=intellij">Contact Us</a></li>
                <li><a href="https://karatedebug.com/license">License</a> - End User License Agreement</li>
            </ul>
        """.trimIndent()

        changeNotes = parseChangelogToHtml()

        vendor {
            name = "j8d"
            email = "ryan@karatedebug.com"
            url = "https://karatedebug.com"
        }
    }
    
    signing {
        // Plugin signing for JetBrains Marketplace
        // For CI: set CERTIFICATE_CHAIN, PRIVATE_KEY, PRIVATE_KEY_PASSWORD env vars
        // For local: create chain.crt and private.pem files in intellij/ directory
        val certChainEnv = providers.environmentVariable("CERTIFICATE_CHAIN")
        val privateKeyEnv = providers.environmentVariable("PRIVATE_KEY")
        val passwordEnv = providers.environmentVariable("PRIVATE_KEY_PASSWORD")

        if (certChainEnv.isPresent && privateKeyEnv.isPresent) {
            certificateChain.set(certChainEnv)
            privateKey.set(privateKeyEnv)
            password.set(passwordEnv)
        } else {
            // Try file-based signing for local development
            val chainFile = layout.projectDirectory.file("chain.crt")
            val keyFile = layout.projectDirectory.file("private.pem")
            if (chainFile.asFile.exists() && keyFile.asFile.exists()) {
                certificateChain.set(providers.fileContents(chainFile).asText)
                privateKey.set(providers.fileContents(keyFile).asText)
                password.set(passwordEnv)
            }
        }
    }

    publishing {
        // Publish token from JetBrains Marketplace account
        // Generate at: https://plugins.jetbrains.com/author/me/tokens
        token.set(providers.environmentVariable("JETBRAINS_PUBLISH_TOKEN"))

        // Channel configuration for EAP/stable releases
        // Set PUBLISH_CHANNEL env var to "eap" for pre-release, or leave empty for stable
        // Users opt into EAP via: Settings → Plugins → ⚙️ → Manage Plugin Repositories
        // Add: https://plugins.jetbrains.com/plugins/eap/list
        val channel = providers.environmentVariable("PUBLISH_CHANNEL").orElse("default")
        channels.set(listOf(channel.get()))
    }
    
    pluginVerification {
        ides {
            // Verify against oldest (compatibility floor) and newest (current) versions
            // Middle versions add little value - if it works on both ends, it works in between
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.1")  // sinceBuild
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")  // current
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.4.1"
    }
    
    // Ensure debug-server JAR is built before compiling
    compileJava {
        dependsOn(":buildDebugServer")
    }
}

// Task to build the debug-server JAR
tasks.register<Exec>("buildDebugServer") {
    workingDir = file("../shared/debug-server")
    commandLine = listOf("mvn", "package", "-q", "-DskipTests")
    
    // Only run if JAR doesn't exist or sources changed
    inputs.dir("../shared/debug-server/src")
    inputs.file("../shared/debug-server/pom.xml")
    outputs.file("../shared/debug-server/target/karate-debug-server-1.0.0.jar")
}

