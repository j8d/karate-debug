plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

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
            sinceBuild = "232"    // IntelliJ 2023.2 (minimum supported)
            untilBuild = "251.*"  // IntelliJ 2025.1 (allow future versions)
        }
        
        description = """
            Debug Karate API tests with breakpoints, step-through debugging, and an integrated test explorer.
            
            <h3>Features</h3>
            <ul>
                <li>Breakpoint debugging for .feature files</li>
                <li>Step-through debugging (step over, step into, step out)</li>
                <li>Variable inspection and modification</li>
                <li>Gutter icons for running and debugging scenarios</li>
                <li>Karate project auto-detection</li>
                <li>Environment switching</li>
            </ul>
        """.trimIndent()
        
        changeNotes = """
            <h3>0.1.0</h3>
            <ul>
                <li>Initial IntelliJ plugin release</li>
                <li>Basic debugging support</li>
                <li>Gutter icons for run/debug</li>
            </ul>
        """.trimIndent()
        
        vendor {
            name = "j8d"
            email = "ryan@karatedebug.com"
            url = "https://karatedebug.com"
        }
    }
    
    signing {
        // Certificate and private key for plugin signing (optional, for marketplace)
        // certificateChain = providers.fileContents(layout.projectDirectory.file("chain.crt")).asText
        // privateKey = providers.fileContents(layout.projectDirectory.file("private.pem")).asText
        // password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    
    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }
    
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.10"
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

