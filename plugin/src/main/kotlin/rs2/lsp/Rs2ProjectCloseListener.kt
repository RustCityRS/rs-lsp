package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.platform.lsp.api.LspServerManager

class Rs2ProjectCloseListener : ProjectCloseListener {
    override fun projectClosing(project: Project) {
        try {
            LspServerManager.getInstance(project)
                .stopAndRestartIfNeeded(Rs2LspServerSupportProvider::class.java)
        } catch (_: Exception) {}
    }

    override fun projectClosed(project: Project) {
        val binary = Rs2LspBinaryManager.EXECUTABLE_NAME
        try {
            ProcessHandle.allProcesses()
                .filter { it.info().command().orElse("").endsWith(binary) }
                .forEach { it.destroy() }
        } catch (_: Exception) {}
    }
}
