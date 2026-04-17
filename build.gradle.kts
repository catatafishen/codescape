plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.github.projectstats"
// Version is set by CI (PLUGIN_VERSION env var) for releases; defaults to a snapshot for local dev.
version = (System.getenv("PLUGIN_VERSION")?.takeIf { it.isNotBlank() }) ?: "0.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.3")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "261.*"
        }
        // Allow CI to inject release notes (HTML) into the plugin's <change-notes>.
        System.getenv("CHANGELOG_FILE")?.takeIf { it.isNotBlank() }?.let { path ->
            val f = file(path)
            if (f.exists()) changeNotes = providers.provider { f.readText() }
        }
    }
    pluginVerification {
        ides {
            // Pin to a known-released IDE to avoid flaky `recommended()` picking
            // versions whose tarballs are not yet published to the download mirror.
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.3")
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }
}
