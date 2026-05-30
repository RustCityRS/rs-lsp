plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "rs2.lsp"
version = "0.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.2")
        bundledModule("intellij.spellchecker")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("262.*")
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN") ?: "")
    }
}
