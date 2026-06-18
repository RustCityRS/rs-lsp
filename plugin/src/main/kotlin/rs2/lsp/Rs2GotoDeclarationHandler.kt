package rs2.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.tree.IElementType

class Rs2GotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        element: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (element == null) return null
        val project = element.project

        // RS2Config files: resolve straight from the editor document. Large `all.*`
        // dumps exceed idea.max.intellisense.filesize, so they have no PSI tokens at
        // the caret — but the declaration index and document text are size-independent.
        val containingVFile = element.containingFile?.virtualFile
        if (editor != null && containingVFile?.fileType is Rs2ConfigFileType) {
            val text = editor.document.charsSequence
            // Resolve references only; leave declarations to the PSI pipeline (small
            // files) / the action wrapper (large files), where they show usages.
            if (Rs2ConfigNav.declarationAtCaret(project, containingVFile, text, offset) == null) {
                val target = Rs2ConfigNav.resolveReference(project, text, offset)
                if (target != null) return arrayOf(target)
            }
        }

        val node = element.node ?: return null
        val tokenType = node.elementType

        val word = element.text
        if (word.isBlank()) return null

        // Return usages for finger pointer; click handled by Rs2GotoDeclarationWrapper
        if (tokenType == Rs2TokenTypes.TRIGGER_SCRIPT_NAME) {
            val name = word.trimStart('~', '@')
            val baseDir = project.guessProjectDir() ?: return null
            val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return null
            val results = mutableListOf<PsiElement>()
            collectScriptUsages(scriptsDir, project, name, results)
            return if (results.isNotEmpty()) results.toTypedArray() else null
        }

        if (tokenType == Rs2TokenTypes.STRING) {
            return null
        }

        val target = findDefinition(word, tokenType, project, element) ?: return null
        return arrayOf(target)
    }

    private fun findDefinition(word: String, tokenType: IElementType, project: Project, element: PsiElement): PsiElement? {
        val baseDir = project.guessProjectDir() ?: return null

        // Script references → go to definition
        if (tokenType == Rs2TokenTypes.PROC_CALL || tokenType == Rs2TokenTypes.JUMP_CALL) {
            val name = word.trimStart('~', '@')
            return findScriptDefinition(name, baseDir, project)
        }

        // Commands
        if (tokenType == Rs2TokenTypes.COMMAND || tokenType == Rs2TokenTypes.TRIGGER_TYPE) {
            return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
        }

        // Identifiers — check mesanim, commands, then entities
        if (tokenType == Rs2TokenTypes.IDENTIFIER || tokenType == Rs2TokenTypes.TRIGGER_SUBJECT) {
            // Check for mesanim tag: <p,neutral> inside string interpolation
            val mesanimName = buildMesanimName(element)
            if (mesanimName != null) {
                Rs2ConfigNav.findEntity(project, mesanimName)?.let { return it }
            }

            val commands = Rs2CommandRegistry.getCommands(project)
            if (word in commands) {
                return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
            }

            // Build the full entity name across colon (e.g. questlist:chompybird)
            val text = element.containingFile.text
            val fullEntity = Rs2ConfigNav.buildFullEntityName(text, element.textOffset, word)
            // dbtable column reference: table:column (e.g. consume_table:consumable).
            if (fullEntity != word && fullEntity.contains(':')) {
                val table = fullEntity.substringBefore(':')
                val column = fullEntity.substringAfter(':')
                if (word == column) {
                    Rs2ConfigNav.findDbColumn(project, table, column)?.let { return it }
                }
            }
            if (fullEntity != word) {
                Rs2ConfigNav.findEntity(project, fullEntity)?.let { return it }
            }

            val entities = Rs2CommandRegistry.getEntities(project)
            if (word in entities) {
                Rs2ConfigNav.findEntity(project, word)?.let { return it }
            }

            // Could be a script name
            return findScriptDefinition(word, baseDir, project)
        }

        // Local variables — search within the current proc/label scope
        if (tokenType == Rs2TokenTypes.LOCAL_VAR) {
            val name = word.trimStart('$')
            return findLocalVarDefinition(name, element.containingFile, element.textOffset)
        }

        // Game variables — search in .varp/.varn/.vars/.varbit config and pack files
        if (tokenType == Rs2TokenTypes.GAME_VAR) {
            return Rs2ConfigNav.findGameVar(project, word.trimStart('%'))
        }

        // Constants
        if (tokenType == Rs2TokenTypes.CONSTANT) {
            return Rs2ConfigNav.findConstant(project, word.trimStart('^'))
        }

        return null
    }

    private fun findLocalVarDefinition(name: String, psiFile: com.intellij.psi.PsiFile, cursorOffset: Int): PsiElement? {
        val text = psiFile.text
        val lines = text.lines()
        val cursorLine = text.substring(0, cursorOffset.coerceAtMost(text.length)).count { it == '\n' }

        var scopeStart = 0
        var scopeEnd = lines.size
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith('[') && trimmed.contains(']')) {
                if (index <= cursorLine) {
                    scopeStart = index
                } else {
                    scopeEnd = index
                    break
                }
            }
        }

        val target = "$$name"
        for (index in scopeStart until scopeEnd) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("[") && !trimmed.startsWith("def_")) continue
            val pos = line.indexOf(target)
            if (pos < 0) continue
            val after = pos + target.length
            if (after < line.length && (line[after].isLetterOrDigit() || line[after] == '_')) continue
            val offset = lines.take(index).sumOf { it.length + 1 } + pos
            return psiFile.findElementAt(offset) ?: psiFile
        }
        return null
    }

    private fun collectScriptUsages(dir: VirtualFile, project: Project, name: String, results: MutableList<PsiElement>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectScriptUsages(child, project, name, results)
            } else if (child.extension == "rs2") {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                for (prefix in listOf("~", "@")) {
                    val target = "$prefix$name"
                    var idx = 0
                    while (idx < text.length) {
                        val pos = text.indexOf(target, idx)
                        if (pos < 0) break
                        val after = pos + target.length
                        if (after < text.length && (text[after].isLetterOrDigit() || text[after] == '_')) {
                            idx = after
                            continue
                        }
                        val el = psiFile.findElementAt(pos)
                        if (el != null) results.add(el)
                        idx = if (after < text.length) after else text.length
                    }
                }
            }
        }
    }

    private fun findScriptDefinition(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return null
        return findInDirectoryRecursive(scriptsDir, project, "rs2") { line ->
            // Match [proc,name] or [label,name] or any [trigger,name]
            line.trimStart().startsWith("[") && line.contains(",$name]")
        }
    }

    fun findStringInterpolationTarget(element: PsiElement, cursorOffset: Int, project: Project): PsiElement? {
        val text = element.text
        val baseOffset = element.textRange.startOffset
        val localOffset = cursorOffset - baseOffset
        if (localOffset < 0 || localOffset >= text.length) return null

        var openAngle = localOffset
        while (openAngle >= 0 && text[openAngle] != '<') {
            if (text[openAngle] == '>') return null
            openAngle--
        }
        if (openAngle < 0) return null
        var closeAngle = localOffset
        while (closeAngle < text.length && text[closeAngle] != '>') {
            if (text[closeAngle] == '<') return null
            closeAngle++
        }
        if (closeAngle >= text.length) return null

        val inner = text.substring(openAngle + 1, closeAngle)
        val innerOffset = localOffset - openAngle - 1
        if (innerOffset < 0 || innerOffset >= inner.length) return null

        var wordStart = innerOffset
        while (wordStart > 0 && (inner[wordStart - 1].isLetterOrDigit() || inner[wordStart - 1] == '_')) wordStart--
        var wordEnd = innerOffset
        while (wordEnd < inner.length && (inner[wordEnd].isLetterOrDigit() || inner[wordEnd] == '_')) wordEnd++
        if (wordStart >= wordEnd) return null
        val word = inner.substring(wordStart, wordEnd)

        val hasVarPrefix = wordStart > 0 && inner[wordStart - 1] == '$'
        val baseDir = project.guessProjectDir() ?: return null

        if (hasVarPrefix) {
            return findLocalVarDefinition(word, element.containingFile, cursorOffset)
        }

        val commands = Rs2CommandRegistry.getCommands(project)
        if (word in commands) {
            return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
        }

        return null
    }

    private fun buildMesanimName(element: PsiElement): String? {
        // Pattern: < IDENT , IDENT > inside string interpolation
        // Clicking on either identifier should build "left,right"
        var left: PsiElement? = element
        var right: PsiElement? = element
        val nextSib = element.nextSibling
        val prevSib = element.prevSibling
        if (nextSib?.text == ",") {
            // Clicked on the left part (e.g., "p")
            val rightIdent = nextSib.nextSibling
            if (rightIdent?.node?.elementType == Rs2TokenTypes.IDENTIFIER) {
                right = rightIdent
            } else return null
        } else if (prevSib?.text == ",") {
            // Clicked on the right part (e.g., "neutral")
            val leftIdent = prevSib.prevSibling
            if (leftIdent?.node?.elementType == Rs2TokenTypes.IDENTIFIER) {
                left = leftIdent
            } else return null
        } else return null
        // Verify we're inside <> (operator tokens)
        val beforeLeft = left?.prevSibling
        val afterRight = right?.nextSibling
        if (beforeLeft?.text != "<" && beforeLeft?.node?.elementType != Rs2TokenTypes.OPERATOR) return null
        if (afterRight?.text != ">" && afterRight?.node?.elementType != Rs2TokenTypes.OPERATOR) return null
        return "${left!!.text},${right!!.text}"
    }

    private fun findInFile(baseDir: VirtualFile, relativePath: String, searchText: String, project: Project): PsiElement? {
        val file = baseDir.findFileByRelativePath(relativePath) ?: return null
        return findLineInFile(file, project) { it.contains(searchText) }
    }

    private fun findLineInFile(file: VirtualFile, project: Project, predicate: (String) -> Boolean): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val text = psiFile.text
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            if (predicate(line)) {
                val offset = lines.take(index).sumOf { it.length + 1 }
                val element = psiFile.findElementAt(offset)
                return element ?: psiFile
            }
        }
        return null
    }

    private fun findInDirectoryRecursive(dir: VirtualFile, project: Project, extension: String, predicate: (String) -> Boolean): PsiElement? {
        for (child in dir.children) {
            if (child.isDirectory) {
                val result = findInDirectoryRecursive(child, project, extension, predicate)
                if (result != null) return result
            } else if (child.extension == extension) {
                val result = findLineInFile(child, project, predicate)
                if (result != null) return result
            }
        }
        return null
    }
}
