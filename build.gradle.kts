import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "hydranav"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Local PyCharm when present (no download); CI downloads the same version instead.
        val localIde = providers.gradleProperty("pycharmPath").orNull
        if (localIde != null && file(localIde).exists()) local(localIde)
        else pycharm(providers.gradleProperty("pycharmVersion"))
        bundledPlugin("org.jetbrains.plugins.yaml")
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// Java and Kotlin both target the JDK running Gradle: PyCharm's own JBR (25), which is what the IDE runs on.
intellijPlatform {
    instrumentCode = false // no GUI forms to instrument
    pluginConfiguration {
        ideaVersion { sinceBuild = "262" }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
