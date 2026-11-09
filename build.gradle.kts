import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // The IDE provides the Kotlin standard library, so stay within the one bundled with the oldest
        // supported IDE (2025.1 ships Kotlin 2.1).
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

dependencies {
    intellijPlatform {
        // Build against an installed IDE when platformLocalPath is set (no ~1.6 GB download);
        // otherwise download IntelliJ IDEA platformVersion.
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath.isNullOrBlank()) {
            intellijIdea(providers.gradleProperty("platformVersion"))
        } else {
            local(localPath)
        }
        // Applying and previewing ChatGPT's patches uses the platform's patch classes (#XIO).
        bundledModule("intellij.platform.vcs.impl")
    }
    // Plain unit tests of logic that doesn't need the IDE running.
    testImplementation("junit:junit:4.13.2")
}

// Only machines with that IDE installed get the extra checks; the setting is ignored everywhere else.
val latestIdeLocalPath = providers.gradleProperty("latestIdeLocalPath").orNull?.takeIf { it.isNotBlank() && file(it).isDirectory }

intellijPlatformTesting {
    runIde {
        // Runs the plugin, still built against the oldest supported IDE, in the newest one.
        if (latestIdeLocalPath != null) {
            register("runIdeLatest") {
                localPath = file(latestIdeLocalPath)
            }
        }
    }
}

tasks.withType<RunIdeTask>().configureEach {
    // The dev machine's GPU makes JCEF's GPU process crash repeatedly ("Too many restarts of the
    // GPU process"), so sandbox runs use software rendering. Overrides the IDE registry key.
    jvmArgs("-Dide.browser.jcef.gpu.disable=true")

    // Opt-in Chrome remote debugging of the sandbox's browsers, e.g. `./gradlew runIde -PjcefDebugPort=9222`,
    // then open chrome://inspect or http://localhost:9222/json. Off by default: anything on this machine
    // could use the port to drive the logged-in ChatGPT session.
    providers.gradleProperty("jcefDebugPort").orNull?.let { jvmArgs("-Dide.browser.jcef.debug.port=$it") }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Not API usage levels: on 2025.1 the bridges Kotlin generates for ToolWindowFactory's default
        // methods count as internal API usage. They stay listed in the report.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, providers.gradleProperty("platformVersion"))
            if (latestIdeLocalPath != null) {
                local(file(latestIdeLocalPath))
            }
        }
    }
}
