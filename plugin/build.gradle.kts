plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "rs2.lsp"
version = "0.3.1"

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

    runIde {
        // RS2 `all.*` dump files (e.g. all.npc ~4 MB) exceed IntelliJ's default
        // 2.5 MB intellisense limit, which disables PSI — and with it goto /
        // find-usages — on those files. Raise it for sandbox testing. (End users
        // set this via Help -> Edit Custom Properties; see the README.)
        systemProperty("idea.max.intellisense.filesize", "20000")
    }
}
