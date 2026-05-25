package rs2.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import java.nio.file.Path

class Rs2LspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "RS2") {

    override fun isSupportedFile(file: VirtualFile): Boolean {
        return file.extension == "rs2"
    }

    override fun createCommandLine(): GeneralCommandLine {
        val lspBinary = findLspBinary()
        return GeneralCommandLine(lspBinary)
    }

    private fun findLspBinary(): String {
        // 1. Environment variable (explicit override)
        System.getenv("RS_LSP_PATH")?.let { path ->
            if (Path.of(path).toFile().exists()) return path
        }

        // 2. Bundled/managed binary (extracted from plugin resources or auto-updated)
        val managed = Rs2LspBinaryManager.getOrExtractBinary()
        if (Path.of(managed).toFile().exists()) return managed

        // 3. Cargo install location (~/.cargo/bin/rs-lsp)
        System.getenv("USERPROFILE")?.let { home ->
            val cargoPath = Path.of(home, ".cargo", "bin", "rs-lsp.exe")
            if (cargoPath.toFile().exists()) return cargoPath.toString()
        }
        System.getenv("HOME")?.let { home ->
            val cargoPath = Path.of(home, ".cargo", "bin", "rs-lsp")
            if (cargoPath.toFile().exists()) return cargoPath.toString()
        }

        // 4. Built from source inside the user's project
        val projectBase = project.basePath
        if (projectBase != null) {
            for (dir in listOf("debug", "release")) {
                for (name in listOf("rs-lsp.exe", "rs-lsp")) {
                    val path = Path.of(projectBase, "target", dir, name)
                    if (path.toFile().exists()) return path.toString()
                }
            }
        }

        // 5. Fallback: PATH
        return "rs-lsp"
    }
}
