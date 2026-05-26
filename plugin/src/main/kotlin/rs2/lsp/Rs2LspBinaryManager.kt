package rs2.lsp

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

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

    private fun getInstallDir(): Path {
        return Path.of(PathManager.getPluginsPath(), "rs-lsp-bin")
    }

    fun getOrExtractBinary(): String {
        val installDir = getInstallDir()
        val binaryPath = installDir.resolve(EXECUTABLE_NAME)

        if (binaryPath.toFile().exists()) {
            return binaryPath.toString()
        }

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
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                binaryPath.toFile().setExecutable(true)
            }

            LOG.info("Extracted LSP binary to $binaryPath")
            return binaryPath.toString()
        } catch (e: Exception) {
            LOG.warn("Failed to extract LSP binary", e)
            return null
        }
    }
}
