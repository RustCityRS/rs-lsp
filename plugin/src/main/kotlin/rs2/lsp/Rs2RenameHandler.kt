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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
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
        val offset = if (editor.selectionModel.hasSelection()) editor.selectionModel.selectionStart else editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return
        val node = element.node ?: return
        val tokenType = node.elementType
        val oldName = element.text

        if (oldName.isBlank()) return

        val nonRenameable = setOf(
            Rs2TokenTypes.TYPE_NAME, Rs2TokenTypes.KEYWORD, Rs2TokenTypes.TRUE,
            Rs2TokenTypes.FALSE, Rs2TokenTypes.NULL, Rs2TokenTypes.TRIGGER_TYPE,
            Rs2TokenTypes.OPERATOR, Rs2TokenTypes.PAREN, Rs2TokenTypes.BRACE,
            Rs2TokenTypes.COMMA, Rs2TokenTypes.SEMICOLON, Rs2TokenTypes.DOT,
            Rs2TokenTypes.TRIGGER_BRACKET, Rs2TokenTypes.NUMBER, Rs2TokenTypes.COORD_LITERAL,
            Rs2TokenTypes.STRING, Rs2TokenTypes.COMMENT, Rs2TokenTypes.WHITESPACE,
            Rs2TokenTypes.NEWLINE, Rs2TokenTypes.CODE
        )
        if (tokenType in nonRenameable) return

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
                // Game vars: rename across .rs2 files, config files, and pack files
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectReplacementsInDirectory(scriptsDir, "rs2", bareName, newName, project, replacements, prefixed = true)
                }
                val packDir = baseDir.findFileByRelativePath("content/pack")
                val contentDir = baseDir.findFileByRelativePath("content")
                if (packDir != null) {
                    for (varPack in listOf("varp.pack", "varn.pack", "vars.pack", "varbit.pack")) {
                        val packFile = packDir.findChild(varPack) ?: continue
                        val psiFile = PsiManager.getInstance(project).findFile(packFile) ?: continue
                        val found = psiFile.text.lines().any { line ->
                            val eqIdx = line.indexOf('=')
                            eqIdx >= 0 && line.substring(eqIdx + 1).trim() == bareName
                        }
                        if (found) {
                            collectReplacementsInFile(packFile, bareName, newName, project, replacements, wholeLineValue = true)
                            val configExt = varPack.removeSuffix(".pack")
                            if (contentDir != null) {
                                collectConfigHeaderReplacements(contentDir, configExt, bareName, newName, project, replacements)
                            }
                        }
                    }
                }
            }
            Rs2TokenTypes.IDENTIFIER, Rs2TokenTypes.TRIGGER_SUBJECT -> {
                // Entity names: rename in config file, pack file, and trigger headers only
                val packDir = baseDir.findFileByRelativePath("content/pack")
                if (packDir != null) {
                    for (packFile in packDir.children.filter { it.extension == "pack" }) {
                        val psiFile = PsiManager.getInstance(project).findFile(packFile) ?: continue
                        val text = psiFile.text
                        val found = text.lines().any { line ->
                            val eqIdx = line.indexOf('=')
                            eqIdx >= 0 && line.substring(eqIdx + 1).trim() == bareName
                        }
                        if (found) {
                            // Rename in the pack file
                            collectReplacementsInFile(packFile, bareName, newName, project, replacements, wholeLineValue = true)
                            // Rename in config files (.obj, .npc, etc.)
                            val configExt = packFile.nameWithoutExtension
                            val contentDir = baseDir.findFileByRelativePath("content")
                            if (contentDir != null) {
                                collectConfigHeaderReplacements(contentDir, configExt, bareName, newName, project, replacements)
                            }
                        }
                    }
                }
                // Rename in trigger headers (excluding proc/label/debugproc) and code usages
                val scriptsDir = baseDir.findFileByRelativePath("content/scripts")
                if (scriptsDir != null) {
                    collectTriggerSubjectReplacements(scriptsDir, bareName, newName, project, replacements)
                    collectEntityCodeUsages(scriptsDir, bareName, newName, project, replacements)
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

        // Save modified files so the LSP re-indexes definitions via didSave
        val modifiedFiles = replacements.map { it.file }.toSet()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val docManager = FileDocumentManager.getInstance()
            for (file in modifiedFiles) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: continue
                docManager.saveDocument(doc)
            }
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

    private fun collectConfigHeaderReplacements(
        dir: VirtualFile, extension: String, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectConfigHeaderReplacements(child, extension, oldName, newName, project, out)
            } else if (child.extension == extension) {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                val target = "[$oldName]"
                var idx = 0
                while (idx < text.length) {
                    val found = text.indexOf(target, idx)
                    if (found < 0) break
                    out.add(Replacement(child, found + 1, oldName.length, newName))
                    idx = found + target.length
                }
            }
        }
    }

    private val SKIP_TRIGGERS = setOf("proc", "label", "debugproc")

    private fun collectTriggerSubjectReplacements(
        dir: VirtualFile, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectTriggerSubjectReplacements(child, oldName, newName, project, out)
            } else if (child.extension == "rs2") {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                var offset = 0
                for (line in text.lines()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("[")) {
                        val commaIdx = trimmed.indexOf(',')
                        val closeBracket = if (commaIdx >= 0) trimmed.indexOf(']', commaIdx) else -1
                        if (commaIdx >= 0 && closeBracket >= 0) {
                            val triggerType = trimmed.substring(1, commaIdx).trim()
                            val subject = trimmed.substring(commaIdx + 1, closeBracket).trim()
                            if (subject == oldName && triggerType !in SKIP_TRIGGERS) {
                                val subjectOffset = offset + line.indexOf(oldName, line.indexOf(','))
                                out.add(Replacement(child, subjectOffset, oldName.length, newName))
                            }
                        }
                    }
                    offset += line.length + 1
                }
            }
        }
    }

    private fun collectEntityCodeUsages(
        dir: VirtualFile, oldName: String, newName: String,
        project: Project, out: MutableList<Replacement>
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectEntityCodeUsages(child, oldName, newName, project, out)
            } else if (child.extension == "rs2") {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                var idx = 0
                while (idx < text.length) {
                    val found = text.indexOf(oldName, idx)
                    if (found < 0) break
                    val afterIdx = found + oldName.length
                    val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit() && text[afterIdx] != '_'
                    val beforeOk = found == 0 || !text[found - 1].isLetterOrDigit() && text[found - 1] != '_'
                    if (beforeOk && afterOk) {
                        val skip = found > 0 && text[found - 1] in listOf('~', '@', '$', '%', '^')
                        val inSkippedTrigger = if (!skip) isInSkippedTriggerHeader(text, found) else true
                        val inString = if (!skip && !inSkippedTrigger) isInsideString(text, found) else true
                        if (!skip && !inSkippedTrigger && !inString) {
                            out.add(Replacement(child, found, oldName.length, newName))
                        }
                    }
                    idx = found + oldName.length
                }
            }
        }
    }

    private fun isInSkippedTriggerHeader(text: String, offset: Int): Boolean {
        var lineStart = offset - 1
        while (lineStart >= 0 && text[lineStart] != '\n') lineStart--
        lineStart++
        val line = text.substring(lineStart, text.indexOf('\n', offset).let { if (it < 0) text.length else it })
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("[")) return false
        val commaIdx = trimmed.indexOf(',')
        if (commaIdx < 0) return false
        val triggerType = trimmed.substring(1, commaIdx).trim()
        return triggerType in SKIP_TRIGGERS
    }

    private fun isInsideString(text: String, offset: Int): Boolean {
        var lineStart = offset - 1
        while (lineStart >= 0 && text[lineStart] != '\n') lineStart--
        lineStart++
        var inString = false
        for (i in lineStart until offset) {
            if (text[i] == '"') inString = !inString
        }
        return inString
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
