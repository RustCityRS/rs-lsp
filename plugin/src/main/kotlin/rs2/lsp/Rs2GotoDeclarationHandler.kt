package rs2.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.io.File

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

        // Try to find definition
        val target = findDefinition(word, tokenType, project) ?: return null
        return arrayOf(target)
    }

    private fun findDefinition(word: String, tokenType: com.intellij.psi.tree.IElementType, project: Project): PsiElement? {
        val baseDir = project.guessProjectDir() ?: return null

        // Script definitions (procs, labels)
        if (tokenType == Rs2TokenTypes.PROC_CALL || tokenType == Rs2TokenTypes.JUMP_CALL ||
            tokenType == Rs2TokenTypes.TRIGGER_SCRIPT_NAME) {
            val name = word.trimStart('~', '@')
            return findScriptDefinition(name, baseDir, project)
        }

        // Commands
        if (tokenType == Rs2TokenTypes.COMMAND || tokenType == Rs2TokenTypes.TRIGGER_TYPE) {
            return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
        }

        // Identifiers — check commands first, then entities
        if (tokenType == Rs2TokenTypes.IDENTIFIER || tokenType == Rs2TokenTypes.TRIGGER_SUBJECT) {
            val commands = Rs2CommandRegistry.getCommands(project)
            if (word in commands) {
                return findInFile(baseDir, "content/scripts/engine.rs2", "[command,$word]", project)
            }

            val entities = Rs2CommandRegistry.getEntities(project)
            if (word in entities) {
                return findInPackFiles(word, baseDir, project)
            }

            // Could be a script name
            return findScriptDefinition(word, baseDir, project)
        }

        // Constants
        if (tokenType == Rs2TokenTypes.CONSTANT) {
            val name = word.trimStart('^')
            return findConstantDefinition(name, baseDir, project)
        }

        return null
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
        return findInDirectoryRecursive(scriptsDir, project, "constant") { line ->
            val trimmed = line.trim()
            val key = trimmed.split('=').firstOrNull()?.trim()?.trimStart('^') ?: ""
            key == name
        }
    }

    private fun findInPackFiles(name: String, baseDir: VirtualFile, project: Project): PsiElement? {
        val packDir = baseDir.findFileByRelativePath("content/pack") ?: return null
        val packFiles = packDir.children.filter { it.extension == "pack" }
        for (packFile in packFiles) {
            val psi = findLineInFile(packFile, project) { line ->
                val eqIdx = line.indexOf('=')
                if (eqIdx >= 0) line.substring(eqIdx + 1).trim() == name else false
            }
            if (psi != null) return psi
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
