package rs2.lsp

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

class Rs2FindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        val tokenType = element.node?.elementType ?: return false
        return tokenType == Rs2TokenTypes.TRIGGER_SCRIPT_NAME ||
                tokenType == Rs2TokenTypes.PROC_CALL ||
                tokenType == Rs2TokenTypes.JUMP_CALL
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        return Rs2ScriptFindUsagesHandler(element)
    }
}

private class Rs2ScriptFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        val name = element.text.trimStart('~', '@')
        val project = element.project
        val baseDir = project.guessProjectDir() ?: return true
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return true
        return searchUsages(scriptsDir, name, processor)
    }

    private fun searchUsages(
        dir: VirtualFile,
        name: String,
        processor: Processor<in UsageInfo>
    ): Boolean {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (!searchUsages(child, name, processor)) return false
            } else if (child.extension == "rs2") {
                if (!searchFileUsages(child, name, processor)) return false
            }
        }
        return true
    }

    private fun searchFileUsages(
        file: VirtualFile,
        name: String,
        processor: Processor<in UsageInfo>
    ): Boolean {
        val psiFile = PsiManager.getInstance(psiElement.project).findFile(file) ?: return true
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
                if (el != null) {
                    if (!processor.process(UsageInfo(el))) return false
                }
                idx = if (after < text.length) after else text.length
            }
        }
        return true
    }
}
