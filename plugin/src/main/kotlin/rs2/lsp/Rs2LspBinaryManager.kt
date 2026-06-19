package rs2.lsp

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginStateListener
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.NioFiles
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object Rs2LspBinaryManager {
    private val LOG = Logger.getInstance(Rs2LspBinaryManager::class.java)

    private const val PLUGIN_ID = "rs2.lsp.rs-lsp-plugin"

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

    val EXECUTABLE_NAME = when {
        System.getProperty("os.name").lowercase().contains("win") -> "rs-lsp.exe"
        else -> "rs-lsp"
    }

    private val cleanupRegistered = AtomicBoolean(false)

    private fun getInstallDir(): Path {
        return Path.of(PathManager.getPluginsPath(), "rs-lsp-bin")
    }

    /**
     * Return the path to the managed LSP binary, (re)extracting the bundled binary
     * whenever the on-disk copy is missing or out of date.
     *
     * The extraction target lives under the persistent plugins directory so it
     * survives across IDE restarts — but that also means a plugin upgrade does NOT
     * by itself refresh it. Keying re-extraction on a content fingerprint (rather
     * than merely "does the file exist") guarantees a newly bundled binary actually
     * replaces a stale one. Without this, an older binary built against an earlier
     * rs-runec keeps running after an upgrade and reintroduces fixed bugs — e.g.
     * the pre-0.2 lexer emitted a `Hash` token for `#`, so the `#testscript`
     * marker was flagged "Unexpected token: Hash '#'".
     */
    fun getOrExtractBinary(): String {
        val installDir = getInstallDir()
        val binaryPath = installDir.resolve(EXECUTABLE_NAME)
        val stampPath = installDir.resolve("$EXECUTABLE_NAME.sha256")

        val bundled = readBundledBinary()
        if (bundled == null) {
            // No bundled binary for this platform — reuse whatever was extracted before.
            return if (Files.exists(binaryPath)) binaryPath.toString() else EXECUTABLE_NAME
        }

        val bundledHash = sha256Hex(bundled)
        if (Files.exists(binaryPath) && readStamp(stampPath) == bundledHash) {
            return binaryPath.toString()
        }

        return extractBinary(installDir, binaryPath, stampPath, bundled, bundledHash)
            ?: if (Files.exists(binaryPath)) binaryPath.toString() else EXECUTABLE_NAME
    }

    /**
     * Remove the extracted binary directory when the plugin itself is uninstalled.
     *
     * The extracted copy is a sibling of the plugin directory, so the platform's
     * own uninstall never touches it; without this it would be orphaned on disk.
     * Registration is idempotent and the listener lives for the IDE session.
     */
    fun registerUninstallCleanup() {
        if (!cleanupRegistered.compareAndSet(false, true)) return
        PluginInstaller.addStateListener(object : PluginStateListener {
            override fun install(descriptor: IdeaPluginDescriptor) {}
            override fun uninstall(descriptor: IdeaPluginDescriptor) {
                if (descriptor.pluginId.idString == PLUGIN_ID) {
                    deleteExtractedBinary()
                }
            }
        })
    }

    private fun deleteExtractedBinary() {
        val installDir = getInstallDir()
        try {
            if (Files.exists(installDir)) {
                // Best-effort: on Windows the binary may still be locked by a running
                // LSP process, in which case the directory is left for the next run.
                NioFiles.deleteRecursively(installDir)
                LOG.info("Removed extracted LSP binary at $installDir")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to remove extracted LSP binary at $installDir", e)
        }
    }

    private fun readBundledBinary(): ByteArray? {
        val stream = Rs2LspBinaryManager::class.java.getResourceAsStream("/bin/$BINARY_NAME")
        if (stream == null) {
            LOG.warn("Bundled binary not found: /bin/$BINARY_NAME")
            return null
        }
        return stream.use { it.readBytes() }
    }

    private fun extractBinary(
        installDir: Path,
        binaryPath: Path,
        stampPath: Path,
        bytes: ByteArray,
        hash: String,
    ): String? {
        var staged: Path? = null
        return try {
            Files.createDirectories(installDir)
            // Invalidate the stamp first: if anything below fails, the next launch
            // sees no stamp, re-hashes and retries — it can never trust a binary
            // that a half-applied update left behind.
            Files.deleteIfExists(stampPath)

            // Stage into a sibling temp file and swap it in atomically, so a crash
            // or a concurrent reader never observes a partially written executable.
            val temp = Files.createTempFile(installDir, "rs-lsp", ".tmp")
            staged = temp
            Files.write(temp, bytes)
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                temp.toFile().setExecutable(true)
            }
            moveIntoPlace(temp, binaryPath)
            staged = null

            Files.writeString(stampPath, hash)
            LOG.info("Extracted LSP binary to $binaryPath")
            binaryPath.toString()
        } catch (e: Exception) {
            // A common cause on Windows is the previous binary still being locked by a
            // running LSP process; the next launch retries once it has exited.
            LOG.warn("Failed to extract LSP binary", e)
            null
        } finally {
            staged?.let { try { Files.deleteIfExists(it) } catch (_: Exception) {} }
        }
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readStamp(stampPath: Path): String? {
        return try {
            if (Files.exists(stampPath)) Files.readString(stampPath).trim() else null
        } catch (e: Exception) {
            LOG.warn("Failed to read LSP binary stamp", e)
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }
}
