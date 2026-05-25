package rs2.lsp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.FileContentUtilCore
import com.intellij.psi.PsiDocumentManager

class Rs2ExtractProcAction : AnAction("Extract RS2 Proc") {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        if (file.fileType != Rs2FileType.INSTANCE) return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Select the code to extract into a proc.", "Extract Proc")
            return
        }

        val selectedText = selectionModel.selectedText ?: return
        val selStart = selectionModel.selectionStart
        val selEnd = selectionModel.selectionEnd

        // Detect local variables used in selection (referenced but defined outside)
        val localVarsUsed = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*)""")
            .findAll(selectedText)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Detect variable types from the file text (look for def_type $name before selection)
        val fullText = editor.document.text
        val textBeforeSelection = fullText.substring(0, selStart)
        val varTypes = mutableMapOf<String, String>()
        for (varName in localVarsUsed) {
            val pattern = Regex("""def_(\w+)\s+\$$varName\b""")
            val match = pattern.find(textBeforeSelection)
            if (match != null) {
                varTypes[varName] = match.groupValues[1]
            }
        }

        // Filter to only params (vars defined before the selection, used in it)
        val params = localVarsUsed.filter { it in varTypes }

        // Ask for proc name
        val procName = Messages.showInputDialog(
            project,
            "Proc name:",
            "Extract Proc",
            null,
            "new_proc",
            null
        ) ?: return

        if (procName.isBlank()) return

        // Build the proc definition
        val paramList = params.joinToString(", ") { "${varTypes[it]} \$$it" }
        val paramHeader = if (paramList.isNotEmpty()) "($paramList)" else ""
        val callArgs = params.joinToString(", ") { "\$$it" }
        val callText = if (callArgs.isNotEmpty()) "~$procName($callArgs)" else "~$procName"

        // Determine indentation of selection
        val doc = editor.document
        val startLine = doc.getLineNumber(selStart)
        val lineStart = doc.getLineStartOffset(startLine)
        val indent = fullText.substring(lineStart, selStart).takeWhile { it == ' ' || it == '\t' }

        // Build proc body (dedent to base level)
        val procBody = selectedText.trimEnd()

        val procDef = "\n[proc,$procName]$paramHeader\n$procBody\n"

        WriteCommandAction.runWriteCommandAction(project, "Extract Proc '$procName'", null, {
            val document = editor.document
            // Replace selection with call
            document.replaceString(selStart, selEnd, "$indent$callText;\n")

            // Append proc definition at end of file
            document.insertString(document.textLength, procDef)

            PsiDocumentManager.getInstance(project).commitDocument(document)
            Rs2CommandRegistry.invalidate(project)
        })

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val openRs2Files = FileEditorManager.getInstance(project).openFiles
                .filter { it.extension == "rs2" }
            if (openRs2Files.isNotEmpty()) {
                FileContentUtilCore.reparseFiles(openRs2Files)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = file?.fileType == Rs2FileType.INSTANCE
    }
}
