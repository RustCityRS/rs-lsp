package rs2.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.FileContentUtilCore

class Rs2FileChangeListener : CommandListener {
    override fun commandFinished(event: CommandEvent) {
        // Refresh only on definition-affecting commands. NOT on "Typing": that
        // fires on essentially every keystroke and would wipe all registry caches
        // (forcing a full re-scan of every pack/script on the next completion/
        // annotate/inlay). Edits reach the registry on save via Rs2VfsListener.
        val name = event.commandName ?: ""
        val isRelevant = name.contains("Rename", ignoreCase = true) ||
                name.contains("Undo", ignoreCase = true) ||
                name.contains("Redo", ignoreCase = true)

        if (!isRelevant) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val hasRs2Open = FileEditorManager.getInstance(project).openFiles
                .any { it.extension == "rs2" }
            if (!hasRs2Open) continue

            Rs2CommandRegistry.invalidate(project)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val openRs2Files = FileEditorManager.getInstance(project).openFiles
                    .filter { it.extension == "rs2" }
                if (openRs2Files.isNotEmpty()) {
                    FileContentUtilCore.reparseFiles(openRs2Files)
                }
            }
        }
    }
}
