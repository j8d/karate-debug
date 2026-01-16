plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

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
    }

    // Gson for DAP JSON protocol
    implementation("com.google.code.gson:gson:2.10.1")

    // Add the shared debug-server JAR
    implementation(files("../shared/debug-server/target/karate-debug-server-1.0.0.jar"))
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "242"    // IntelliJ 2024.2 (minimum supported)
            untilBuild = "253.*"  // IntelliJ 2025.3 (allow future patch versions)
        }

        description = """
            <p>Debug Karate API tests with full breakpoint support,
            step-through debugging, variable inspection, and real-time match diagnostics.</p>

            <h3>Features</h3>
            <ul>
                <li><b>Breakpoint Debugging</b> - Set breakpoints in .feature files and step through your tests</li>
                <li><b>Variable Inspection</b> - View and modify variables during debug sessions</li>
                <li><b>Match Diagnostics</b> - See pass/fail highlights on match statements with actual values inline</li>
                <li><b>Quick Fixes</b> - One-click fixes to update expected values from actual results</li>
                <li><b>Conditional Breakpoints</b> - Set conditions on breakpoints using JavaScript expressions</li>
                <li><b>Gutter Icons</b> - Debug individual features or scenarios from the editor</li>
                <li><b>Feature Explorer</b> - Browse and run tests from the Karate tool window</li>
                <li><b>Project Auto-Detection</b> - Automatically detects Maven/Gradle Karate projects</li>
                <li><b>Environment Switching</b> - Quick switch between Karate environments</li>
                <li><b>Syntax Highlighting</b> - Full syntax highlighting for Karate feature files</li>
            </ul>

            <h3>Getting Started</h3>
            <ol>
                <li>Open a project containing Karate tests</li>
                <li>Open a .feature file</li>
                <li>Set breakpoints by clicking in the gutter</li>
                <li>Click the debug icon in the gutter or use the Karate tool window</li>
            </ol>

            <p>Requires Java 17+ and a Karate project with Maven or Gradle.</p>

            <h3>Resources</h3>
            <ul>
                <li><a href="https://karatedebug.com">Website</a> - Documentation and getting started guides</li>
                <li><a href="https://karatedebug.com/?contact=bug">Report a Bug</a></li>
                <li><a href="https://karatedebug.com/?contact=feature">Request a Feature</a></li>
                <li><a href="https://karatedebug.com/?contact=general">Contact Us</a></li>
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
    }
    
    pluginVerification {
        ides {
            // Verify against specific IDE versions we want to support
            // 2024.2 (242), 2024.3 (243), 2025.1 (251)
            ide("IC", "2024.2.4")
            ide("IC", "2024.3.4")
            ide("IC", "2025.1.1")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.14.3"
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

