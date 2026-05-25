package rs2.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

object Rs2LspBinaryManager {
    private val LOG = Logger.getInstance(Rs2LspBinaryManager::class.java)

    private val BINARY_NAME: String = run {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val target = when {
            os.contains("win") && arch == "amd64" -> "x86_64-pc-windows-msvc"
            os.contains("win") && arch == "aarch64" -> "aarch64-pc-windows-msvc"
            os.contains("mac") && arch == "amd64" -> "x86_64-apple-darwin"
            os.contains("mac") && arch == "aarch64" -> "aarch64-apple-darwin"
            arch == "amd64" -> "x86_64-unknown-linux-gnu"
            arch == "aarch64" -> "aarch64-unknown-linux-gnu"
            else -> "x86_64-unknown-linux-gnu"
        }
        val ext = if (os.contains("win")) ".exe" else ""
        "rs-lsp-$target$ext"
    }

    private val EXECUTABLE_NAME = when {
        System.getProperty("os.name").lowercase().contains("win") -> "rs-lsp.exe"
        else -> "rs-lsp"
    }

    private const val GITHUB_API = "https://api.github.com/repos/RustCityRS/rs-lsp/releases/latest"
    private const val VERSION_FILE = "version.txt"

    private fun getInstallDir(): Path {
        return Path.of(PathManager.getPluginsPath(), "rs-lsp-bin")
    }

    fun getOrExtractBinary(): String {
        val installDir = getInstallDir()
        val binaryPath = installDir.resolve(EXECUTABLE_NAME)

        // If already extracted, use it
        if (binaryPath.toFile().exists()) {
            return binaryPath.toString()
        }

        // Extract from bundled resources
        return extractBundledBinary() ?: EXECUTABLE_NAME
    }

    private fun extractBundledBinary(): String? {
        val installDir = getInstallDir()
        Files.createDirectories(installDir)
        val binaryPath = installDir.resolve(EXECUTABLE_NAME)

        val resourceStream = Rs2LspBinaryManager::class.java.getResourceAsStream("/bin/$BINARY_NAME")
        if (resourceStream == null) {
            LOG.warn("Bundled binary not found: /bin/$BINARY_NAME")
            return null
        }

        try {
            FileOutputStream(binaryPath.toFile()).use { out ->
                resourceStream.copyTo(out)
            }
            // Make executable on Unix
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                binaryPath.toFile().setExecutable(true)
            }

            // Write version from plugin
            val version = Rs2LspBinaryManager::class.java.getResource("/META-INF/plugin.xml")?.let { "bundled" } ?: "unknown"
            installDir.resolve(VERSION_FILE).toFile().writeText(version)

            LOG.info("Extracted LSP binary to $binaryPath")
            return binaryPath.toString()
        } catch (e: Exception) {
            LOG.warn("Failed to extract LSP binary", e)
            return null
        }
    }

    fun checkForUpdatesAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                checkForUpdates()
            } catch (e: Exception) {
                LOG.info("Update check failed: ${e.message}")
            }
        }
    }

    private fun checkForUpdates() {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(GITHUB_API))
            .header("Accept", "application/vnd.github.v3+json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return

        val body = response.body()
        val tagName = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return

        val installDir = getInstallDir()
        val versionFile = installDir.resolve(VERSION_FILE).toFile()
        val currentVersion = if (versionFile.exists()) versionFile.readText().trim() else "none"

        if (tagName == currentVersion) return

        // Find the download URL for our platform's binary
        val assetPattern = Regex(""""browser_download_url"\s*:\s*"([^"]*$BINARY_NAME)"""")
        val downloadUrl = assetPattern.find(body)?.groupValues?.get(1) ?: return

        LOG.info("New LSP version available: $tagName (current: $currentVersion)")

        // Download the new binary
        val downloadRequest = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        val downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray())
        if (downloadResponse.statusCode() != 200) return

        Files.createDirectories(installDir)
        val binaryPath = installDir.resolve(EXECUTABLE_NAME)
        val tempPath = installDir.resolve("$EXECUTABLE_NAME.tmp")

        // Write to temp file first, then rename
        Files.write(tempPath, downloadResponse.body())
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            tempPath.toFile().setExecutable(true)
        }

        // On Windows, the running binary can't be replaced directly.
        // Rename old to .old, rename new to current.
        val oldPath = installDir.resolve("$EXECUTABLE_NAME.old")
        try {
            if (binaryPath.toFile().exists()) {
                Files.deleteIfExists(oldPath)
                Files.move(binaryPath, oldPath)
            }
            Files.move(tempPath, binaryPath)
            Files.deleteIfExists(oldPath)
        } catch (e: Exception) {
            // If rename fails (binary in use), the update will apply on next restart
            LOG.info("Binary in use, update will apply on next restart: ${e.message}")
            return
        }

        versionFile.writeText(tagName)
        LOG.info("Updated LSP binary to $tagName")

        // Notify user
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("RS2 LSP")
                    .createNotification(
                        "RS2 LSP updated to $tagName",
                        "Restart the IDE to use the new version.",
                        NotificationType.INFORMATION
                    )
                    .notify(null)
            } catch (_: Exception) {
                // Notification group might not be registered, that's fine
            }
        }
    }
}
