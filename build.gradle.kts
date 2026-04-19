import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    jacoco
}

group = "com.github.codescape"
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
        pluginVerifier()
    }
    testImplementation(kotlin("stdlib"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
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
            // Verify against 2025.3 where ToolWindowFactory.getIcon/getAnchor/manage are
            // @Experimental (not @Internal as in 242–252). Kotlin generates bridge methods
            // for all Java interface defaults, so any ToolWindowFactory impl inherits them.
            // No alternative API exists — these are unavoidable inherited defaults.
            // Since 2025.3, IC artifacts moved to "intellijIdea" (no longer "ideaIC").
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3")
        }
        // Fail the build on internal API usages and real compatibility problems.
        // Deprecated and experimental API usages are reported as warnings but do NOT
        // fail the build — our ToolWindowFactory inherits deprecated/experimental
        // default methods (isApplicable, isDoNotActivateOnStart, getAnchor, getIcon,
        // manage) that we don't call or override; the platform invokes them on our
        // instance. We already use the modern replacements (shouldBeAvailable,
        // plugin.xml anchor/icon attributes). If JetBrains removes these defaults,
        // no code change on our side is needed — only a platform-plugin version bump.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.MISSING_DEPENDENCIES,
        )
    }
    buildSearchableOptions = false
}

// Plain JUnit test task (no IntelliJ platform classloader) so jacoco can instrument our classes.
// The IntelliJ-platform plugin reconfigures `test` to launch under the JBR/PathClassLoader, which
// prevents the jacoco agent from seeing class definitions for our production code. Running a
// parallel `unitTest` task with a stock JVM keeps pure-Kotlin unit tests measurable.
val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs plain JUnit 5 unit tests outside the IntelliJ platform classloader."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.check {
    dependsOn(unitTest)
}

tasks.jacocoTestReport {
    dependsOn(unitTest)
    executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/unitTest.exec"))
    // Keep coverage focused on the production classes we actually exercise from unit tests.
    // IntelliJ-platform post-instrumentation is irrelevant here because the unitTest task
    // runs on a stock JVM and loads the unmodified kotlin/main classes.
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
            include("com/github/projectstats/**")
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }
}
