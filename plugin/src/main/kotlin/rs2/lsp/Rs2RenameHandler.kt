package rs2.lsp

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.FileContentUtilCore
import com.intellij.refactoring.rename.RenameHandler

class Rs2RenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        return file.fileType == Rs2FileType.INSTANCE
    }

    override fun isRenaming(dataContext: DataContext): Boolean {
        return isAvailableOnDataContext(dataContext)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
        if (editor == null || file == null) return
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val node = element.node ?: return
        val tokenType = node.elementType
        val oldName = element.text

        if (oldName.isBlank()) return

        // Strip prefix for display
        val displayName = when (tokenType) {
            Rs2TokenTypes.PROC_CALL -> oldName.trimStart('~')
            Rs2TokenTypes.JUMP_CALL -> oldName.trimStart('@')
            Rs2TokenTypes.CONSTANT -> oldName.trimStart('^')
            Rs2TokenTypes.LOCAL_VAR -> oldName.trimStart('$')
            Rs2TokenTypes.GAME_VAR -> oldName.trimStart('%')
            else -> oldName
        }

        val newName = Messages.showInputDialog(
            project,
            "Rename '$displayName' to:",
            "Rename",
            null,
            displayName,
            null
        ) ?: return

        if (newName == displayName || newName.isBlank()) return

        performRename(project, element, tokenType, oldName, displayName, newName)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // Not used for editor-based rename
    }

    private fun performRename(
        project: Project,
        element: PsiElement,
        tokenType: com.intellij.psi.tree.IElementType,
        oldName: String,
        bareName: String,
        newName: String
    ) {
        val baseDir = project.guessProjectDir() ?: return

        val replacements = mutableListOf<Replacement>()

        when (tokenType) {
            Rs2TokenTypes.LOCAL_VAR -> {
                // Local vars: only rename within the current script block
                collectLocalVarReplacements(element.containingFile, bareName, newName, replacements)
            }
            Rs2TokenTypes.PROC_CALL, Rs2TokenTypes.JUMP_CALL, Rs2TokenTypes.TRIGGER_SCRIPT_NAME -> {
                // Proc/label names: rename across all .rs2 files
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = true)
                }
            }
            Rs2TokenTypes.COMMAND, Rs2TokenTypes.TRIGGER_TYPE -> {
                // Commands: rename in engine.rs2 and all .rs2 files + command.pack
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = false)
                }
                val commandPack = baseDir.findFileByRelativePath("content/pack/command.pack")
                if (commandPack != null) {
                    collectReplacementsInFile(commandPack, bareName, newName, project, replacements, wholeLineValue = true)
                }
            }
            Rs2TokenTypes.CONSTANT -> {
                // Constants: rename in .rs2 and .constant files
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = true)
                    collectReplacementsInDirectory(scriptsDir, "constant", bareName, newName, project, replacements, prefixed = false)
                }
            }
            Rs2TokenTypes.GAME_VAR -> {
                // Game vars: rename across .rs2 files
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = true)
                }
            }
            Rs2TokenTypes.IDENTIFIER, Rs2TokenTypes.TRIGGER_SUBJECT -> {
                // Entity names: rename in .rs2 and .pack files
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = false)
                }
                val packDir = baseDir.findFileByRelativePath("content/pack")
                if (packDir != null) {
                    for (packFile in packDir.children.filter { it.extension == "pack" }) {
                        collectReplacementsInFile(packFile, bareName, newName, project, replacements, wholeLineValue = true)
                    }
                }
            }
        }

        if (replacements.isEmpty()) return

        WriteCommandAction.runWriteCommandAction(project, "Rename '$bareName' to '$newName'", null, {
            val psiManager = PsiManager.getInstance(project)
            val docManager = PsiDocumentManager.getInstance(project)

            // Group by file, apply in reverse offset order
            val byFile = replacements.groupBy { it.file }
            for ((file, reps) in byFile) {
                val psiFile = psiManager.findFile(file) ?: continue
                val doc = docManager.getDocument(psiFile) ?: continue
                for (rep in reps.sortedByDescending { it.offset }) {
                    doc.replaceString(rep.offset, rep.offset + rep.oldLen, rep.newText)
                }
                docManager.commitDocument(doc)
            }

            Rs2CommandRegistry.invalidate(project)
        })

        // Force reparse of all modified RS2 files to refresh highlighting immediately
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val openRs2Files = FileEditorManager.getInstance(project).openFiles
                .filter { it.extension == "rs2" }
            if (openRs2Files.isNotEmpty()) {
                FileContentUtilCore.reparseFiles(openRs2Files)
            }
        }
    }

    private data class Replacement(val file: VirtualFile, val offset: Int, val oldLen: Int, val newText: String)

    private fun collectLocalVarReplacements(
        psiFile: PsiFile,
        oldName: String,
        newName: String,
        out: MutableList<Replacement>
    ) {
        val text = psiFile.text
        val file = psiFile.virtualFile ?: return
        val patterns = listOf("\$$oldName", "\$$newName")
        val searchFor = "\$$oldName"
        val replaceWith = "\$$newName"
        var idx = 0
        while (idx < text.length) {
            val found = text.indexOf(searchFor, idx)
            if (found < 0) break
            // Check word boundary after
            val afterIdx = found + searchFor.length
            val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_'
            if (afterOk) {
                out.add(Replacement(file, found, searchFor.length, replaceWith))
            }
            idx = found + searchFor.length
        }
    }

    private fun collectReplacementsInDirectory(
        dir: VirtualFile, ext: String, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>, prefixed: Boolean
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectReplacementsInDirectory(child, ext, oldName, newName, project, out, prefixed)
            } else if (child.extension == ext) {
                if (prefixed) {
                    collectPrefixedReplacements(child, oldName, newName, project, out)
                } else {
                    collectWholeWordReplacements(child, oldName, newName, project, out)
                }
            }
        }
    }

    private fun collectPrefixedReplacements(
        file: VirtualFile, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val text = psiFile.text
        // Find all occurrences where oldName appears after a prefix char or as part of [trigger,name]
        val prefixes = listOf("~", "@", "^", "\$", "%", ",")
        for (prefix in prefixes) {
            val search = "$prefix$oldName"
            var idx = 0
            while (idx < text.length) {
                val found = text.indexOf(search, idx)
                if (found < 0) break
                val afterIdx = found + search.length
                val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_'
                if (afterOk) {
                    out.add(Replacement(file, found + prefix.length, oldName.length, newName))
                }
                idx = found + search.length
            }
        }
    }

    private fun collectWholeWordReplacements(
        file: VirtualFile, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val text = psiFile.text
        var idx = 0
        while (idx < text.length) {
            val found = text.indexOf(oldName, idx)
            if (found < 0) break
            val beforeOk = found == 0 || !text[found - 1].isLetterOrDigit() && text[found - 1] != '_'
            val afterIdx = found + oldName.length
            val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_'
            if (beforeOk && afterOk) {
                out.add(Replacement(file, found, oldName.length, newName))
            }
            idx = found + oldName.length
        }
    }

    private fun collectReplacementsInFile(
        file: VirtualFile, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>, wholeLineValue: Boolean
    ) {
        if (wholeLineValue) {
            // In pack files, format is id=name — only replace the value part
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
            val text = psiFile.text
            val search = "=$oldName"
            var idx = 0
            while (idx < text.length) {
                val found = text.indexOf(search, idx)
                if (found < 0) break
                val afterIdx = found + search.length
                val afterOk = afterIdx >= text.length || text[afterIdx] == '\n' || text[afterIdx] == '\r'
                if (afterOk) {
                    out.add(Replacement(file, found + 1, oldName.length, newName))
                }
                idx = found + search.length
            }
        } else {
            collectWholeWordReplacements(file, oldName, newName, project, out)
        }
    }
}
