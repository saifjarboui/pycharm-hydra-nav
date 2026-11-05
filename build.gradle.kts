import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.jetbrains:markdown:0.7.3") } // README -> Marketplace description
}

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
        // Marketplace description = README between the "Plugin description" markers, images made absolute.
        description = file("README.md").readText().substringAfter("<!-- Plugin description -->")
            .substringBefore("<!-- Plugin description end -->").trim().let {
                val md = it.replace("](docs/", "](https://raw.githubusercontent.com/saifjarboui/pycharm-hydra-nav/main/docs/")
                val flavour = GFMFlavourDescriptor()
                HtmlGenerator(md, MarkdownParser(flavour).buildMarkdownTreeFromString(md), flavour).generateHtml()
                    .removePrefix("<body>").removeSuffix("</body>")
            }
        // Marketplace "What's New" = CHANGELOG.md (one line per release; **bold** and `code` only).
        changeNotes = file("CHANGELOG.md").readLines().filter { it.isNotBlank() }.joinToString("<br/>") {
            it.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace(Regex("`(.+?)`"), "<code>$1</code>")
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}
