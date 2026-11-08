plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
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
        // JCEF (JBCefBrowser etc.) is a separate bundled plugin since build 263 (#R4F).
        bundledPlugin("com.intellij.modules.jcef")
    }
    // Plain unit tests of logic that doesn't need the IDE running.
    testImplementation("junit:junit:4.13.2")
}

tasks.runIde {
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
}
