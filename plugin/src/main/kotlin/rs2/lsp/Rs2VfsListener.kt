package rs2.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.psi.PsiManager
import com.intellij.util.FileContentUtilCore

class Rs2VfsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val hasRs2Changes = events.any { event ->
            event is VFileContentChangeEvent && event.file.extension in listOf("rs2", "pack", "constant")
        }
        if (!hasRs2Changes) return

        ApplicationManager.getApplication().invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue

                Rs2CommandRegistry.invalidate(project)
                Rs2TestScopeRegistry.invalidate(project)

                LspServerManager.getInstance(project)
                    .stopAndRestartIfNeeded(Rs2LspServerSupportProvider::class.java)

                val openRs2Files = FileEditorManager.getInstance(project).openFiles
                    .filter { it.extension == "rs2" }
                if (openRs2Files.isNotEmpty()) {
                    FileContentUtilCore.reparseFiles(openRs2Files)
                }
            }
        }
    }
}
