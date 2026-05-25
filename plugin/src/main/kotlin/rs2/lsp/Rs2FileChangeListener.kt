package rs2.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.FileContentUtilCore

class Rs2FileChangeListener : CommandListener {
    override fun commandFinished(event: CommandEvent) {
        // Check if the command name suggests a rename or undo/redo
        val name = event.commandName ?: ""
        val isRelevant = name.contains("Rename") ||
                name.contains("Undo") ||
                name.contains("Redo") ||
                name.contains("undo", ignoreCase = true) ||
                name.contains("redo", ignoreCase = true) ||
                name.contains("Typing")

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
