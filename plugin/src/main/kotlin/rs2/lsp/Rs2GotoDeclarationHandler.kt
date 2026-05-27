package rs2.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement

class Rs2GotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        element: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (element == null) return null
        val node = element.node ?: return null
        val tokenType = node.elementType
        val project = element.project

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

        // Try to find definition
        val target = findDefinition(word, tokenType, project, element) ?: return null
        return arrayOf(target)
    }

    private fun findDefinition(word: String, tokenType: com.intellij.psi.tree.IElementType, project: Project, element: PsiElement): PsiElement? {
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
                val result = findInConfigFiles(mesanimName, "mesanim", baseDir, project)
                    ?: findEntityInSpecificPack(mesanimName, "mesanim", baseDir, project)
                if (result != null) return result
            }

            val commands = Rs2CommandRegistry.getCommands(project)
            if (word in commands) {
                return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
            }

            // Build the full entity name across colon (e.g. questlist:chompybird)
            val text = element.containingFile.text
            val fullEntity = buildFullEntityName(text, element.textOffset, word)
            if (fullEntity != word) {
                val result = findEntityDefinition(fullEntity, baseDir, project)
                if (result != null) return result
            }

            val entities = Rs2CommandRegistry.getEntities(project)
            if (word in entities) {
                val result = findEntityDefinition(word, baseDir, project)
                if (result != null) return result
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
            val name = word.trimStart('%')
            return findGameVarDefinition(name, baseDir, project)
        }

        // Constants
        if (tokenType == Rs2TokenTypes.CONSTANT) {
            val name = word.trimStart('^')
            return findConstantDefinition(name, baseDir, project)
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

    private fun findConstantDefinition(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return null
        return findInDirectoryRecursiveWithOffset(scriptsDir, project, "constant") { line ->
            val trimmed = line.trim()
            val key = trimmed.split('=').firstOrNull()?.trim()?.trimStart('^') ?: ""
            if (key == name) {
                val caretIdx = line.indexOf('^')
                if (caretIdx >= 0) caretIdx else 0
            } else -1
        }
    }

    private fun findInDirectoryRecursiveWithOffset(dir: VirtualFile, project: Project, extension: String, matcher: (String) -> Int): PsiElement? {
        for (child in dir.children) {
            if (child.isDirectory) {
                val result = findInDirectoryRecursiveWithOffset(child, project, extension, matcher)
                if (result != null) return result
            } else if (child.extension == extension) {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                val lines = text.lines()
                for ((index, line) in lines.withIndex()) {
                    val col = matcher(line)
                    if (col >= 0) {
                        val offset = lines.take(index).sumOf { it.length + 1 } + col
                        return OffsetNavigatableElement(psiFile, offset)
                    }
                }
            }
        }
        return null
    }

    private fun findEntityDefinition(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val packDir = baseDir.findFileByRelativePath("content/pack") ?: return findInPackFiles(name, baseDir, project)
        for (packFile in packDir.children.filter { it.extension == "pack" }) {
            val packText = packFile.inputStream.bufferedReader().readText()
            val found = packText.lines().any { line ->
                val eqIdx = line.indexOf('=')
                eqIdx >= 0 && line.substring(eqIdx + 1).trim() == name
            }
            if (found) {
                val configExt = configExtension(packFile.nameWithoutExtension)
                val configResult = findInConfigFiles(name, configExt, baseDir, project)
                if (configResult != null) return configResult
                return findEntityInPackFile(name, packFile, project)
            }
        }
        return findInPackFiles(name, baseDir, project)
    }

    private fun findGameVarDefinition(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val varPacks = listOf("varp", "varn", "vars", "varbit")
        for (packType in varPacks) {
            val packFile = baseDir.findFileByRelativePath("content/pack/$packType.pack") ?: continue
            val result = findEntityInPackFile(name, packFile, project)
            if (result != null) {
                // Try config file first
                val configExt = configExtension(packType)
                val configResult = findInConfigFiles(name, configExt, baseDir, project)
                return configResult ?: result
            }
        }
        return null
    }

    private fun configExtension(packType: String): String = when (packType) {
        "interface" -> "if"
        "namedobj" -> "obj"
        else -> packType
    }

    private fun findInConfigFiles(name: String, extension: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val contentDir = baseDir.findFileByRelativePath("content") ?: return null
        return searchConfigRecursive(contentDir, project, extension, name)
    }

    private fun searchConfigRecursive(dir: VirtualFile, project: Project, extension: String, name: String): PsiElement? {
        for (child in dir.children) {
            if (child.isDirectory) {
                val result = searchConfigRecursive(child, project, extension, name)
                if (result != null) return result
            } else if (child.extension == extension) {
                val psiFile = PsiManager.getInstance(project).findFile(child) ?: continue
                val text = psiFile.text
                val lines = text.lines()
                val target = "[$name]"
                for ((index, line) in lines.withIndex()) {
                    if (line.trimStart() == target) {
                        val offset = lines.take(index).sumOf { it.length + 1 } + line.indexOf('[')
                        return OffsetNavigatableElement(psiFile, offset)
                    }
                }
            }
        }
        return null
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

    private fun findEntityInSpecificPack(name: String, packType: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val packFile = baseDir.findFileByRelativePath("content/pack/$packType.pack") ?: return null
        return findEntityInPackFile(name, packFile, project)
    }

    private fun isEntityChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '+'

    private fun buildFullEntityName(text: String, wordOffset: Int, word: String): String {
        var start = wordOffset
        var end = wordOffset + word.length
        // Expand left across colon/plus+identifier
        while (start > 0 && (text[start - 1] == ':' || text[start - 1] == '+')) {
            var s = start - 2
            while (s >= 0 && isEntityChar(text[s])) s--
            start = s + 1
        }
        // Expand right across colon/plus+identifier
        while (end < text.length && (text[end] == ':' || text[end] == '+')) {
            var e = end + 1
            while (e < text.length && isEntityChar(text[e])) e++
            end = e
        }
        return text.substring(start, end)
    }

    private fun findInPackFiles(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val packDir = baseDir.findFileByRelativePath("content/pack") ?: return null
        for (packFile in packDir.children.filter { it.extension == "pack" }) {
            val result = findEntityInPackFile(name, packFile, project)
            if (result != null) return result
        }
        return null
    }

    private fun findEntityInPackFile(name: String, packFile: VirtualFile, project: Project): PsiElement? {
        val psiFile = PsiManager.getInstance(project).findFile(packFile) ?: return null
        val text = psiFile.text
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val eqIdx = line.indexOf('=')
            if (eqIdx < 0) continue
            if (line.substring(eqIdx + 1).trim() != name) continue
            val namePos = line.indexOf(name, eqIdx)
            val offset = lines.take(index).sumOf { it.length + 1 } + (if (namePos >= 0) namePos else eqIdx + 1)
            return OffsetNavigatableElement(psiFile, offset)
        }
        return null
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

private class OffsetNavigatableElement(
    private val psiFile: PsiFile,
    private val targetOffset: Int
) : FakePsiElement() {
    override fun getParent(): PsiElement = psiFile
    override fun getContainingFile(): PsiFile = psiFile
    override fun getTextOffset(): Int = targetOffset
    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    private val symbolName: String by lazy {
        val text = psiFile.text
        var end = targetOffset
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '^' || text[end] == '+')) end++
        if (targetOffset < text.length) text.substring(targetOffset, end) else psiFile.name
    }
    override fun getName(): String = symbolName
    override fun getText(): String = symbolName
    override fun getPresentation(): com.intellij.ide.projectView.PresentationData {
        return com.intellij.ide.projectView.PresentationData(getName(), psiFile.virtualFile?.path ?: "", null, null)
    }
    override fun toString(): String = getName()
    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(psiFile.project, psiFile.virtualFile, targetOffset).navigate(requestFocus)
    }
}
