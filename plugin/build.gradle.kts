plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "rs2.lsp"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // For local development, point to your RustRover installation.
        // CI builds use intellijIdeaCommunity instead.
        val forceCI = providers.gradleProperty("forceCI").isPresent
        val localIde = file("C:/Program Files/JetBrains/RustRover 2026.1.1")
        if (!forceCI && localIde.exists()) {
            local(localIde.absolutePath)
        } else {
            intellijIdea("2025.3")
        }
    }
}

// Spellchecker API (bundled with the IDE)
val spellcheckerJar = file("C:/Program Files/JetBrains/RustRover 2026.1.1/lib/intellij.spellchecker.jar")
if (spellcheckerJar.exists()) {
    dependencies {
        compileOnly(files(spellcheckerJar))
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
