package rs2.lsp

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressManager
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
                tokenType == Rs2TokenTypes.JUMP_CALL ||
                tokenType == Rs2ConfigTokenTypes.SECTION ||
                tokenType == Rs2ConfigTokenTypes.SECTION_NAME ||
                tokenType == Rs2ConfigTokenTypes.PACK_NAME
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler {
        val t = element.node?.elementType
        return if (t == Rs2ConfigTokenTypes.SECTION ||
            t == Rs2ConfigTokenTypes.SECTION_NAME ||
            t == Rs2ConfigTokenTypes.PACK_NAME
        ) {
            Rs2ConfigEntityFindUsagesHandler(element)
        } else {
            Rs2ScriptFindUsagesHandler(element)
        }
    }
}

private class Rs2ScriptFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        // element.text / project access touch PSI → wrap in a read action (Find
        // Usages can invoke this on a pooled thread that holds no read lock).
        val (name, scriptsDir) = runReadAction {
            element.text.trimStart('~', '@') to
                element.project.guessProjectDir()?.findFileByRelativePath("content/scripts")
        }
        if (scriptsDir == null) return true
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

    // PSI access (findFile/findElementAt) must run inside a read action; Find
    // Usages calls this on a background pooled thread that holds no read lock.
    private fun searchFileUsages(
        file: VirtualFile,
        name: String,
        processor: Processor<in UsageInfo>
    ): Boolean {
        ProgressManager.checkCanceled()
        return runReadAction {
            val psiFile = PsiManager.getInstance(psiElement.project).findFile(file)
                ?: return@runReadAction true
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
                    if (el != null && !processor.process(UsageInfo(el))) return@runReadAction false
                    idx = if (after < text.length) after else text.length
                }
            }
            true
        }
    }
}

/**
 * Find Usages for a config entity (section header like `[inv_83]`). Searches all
 * .rs2 scripts and config files under content/scripts for whole-word references
 * to the entity name. Skips the generated `_unpack` dumps and the entity's own
 * `[name]` declaration header.
 */
private class Rs2ConfigEntityFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        // element.text / project access touch PSI → wrap in a read action (Find
        // Usages can invoke this on a pooled thread that holds no read lock).
        val (name, scriptsDir) = runReadAction {
            element.text to
                element.project.guessProjectDir()?.findFileByRelativePath("content/scripts")
        }
        if (name.isBlank() || scriptsDir == null) return true
        return searchEntityUsages(scriptsDir, name, processor)
    }

    private fun searchEntityUsages(
        dir: VirtualFile,
        name: String,
        processor: Processor<in UsageInfo>
    ): Boolean {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name == "_unpack") continue // generated dumps — not real usages
                if (!searchEntityUsages(child, name, processor)) return false
            } else if (!searchEntityFileUsages(child, name, processor)) {
                return false
            }
        }
        return true
    }

    // PSI access must run inside a read action (see note above).
    private fun searchEntityFileUsages(
        file: VirtualFile,
        name: String,
        processor: Processor<in UsageInfo>
    ): Boolean {
        ProgressManager.checkCanceled()
        return runReadAction {
            val psiFile = PsiManager.getInstance(psiElement.project).findFile(file)
                ?: return@runReadAction true
            val text = psiFile.text
            if (!text.contains(name)) return@runReadAction true // cheap reject

            var idx = 0
            while (idx < text.length) {
                val pos = text.indexOf(name, idx)
                if (pos < 0) break
                val afterPos = pos + name.length
                val before = if (pos > 0) text[pos - 1] else ' '
                val after = if (afterPos < text.length) text[afterPos] else ' '
                val wholeWord = !isIdentChar(before) && !isIdentChar(after)
                val isOwnDeclaration = before == '[' && after == ']'
                if (wholeWord && !isOwnDeclaration) {
                    // Require the leaf at this offset to be exactly the name. This
                    // drops matches inside comments/strings/`$locals` (where the
                    // leaf's text is the whole comment/string/var, not the bare
                    // name) while keeping real identifier refs, including ones
                    // tokenized inside string interpolation like `<p,short>`.
                    val el = psiFile.findElementAt(pos)
                    if (el != null && el.text == name) {
                        if (!processor.process(UsageInfo(el))) return@runReadAction false
                    }
                }
                idx = afterPos
            }
            true
        }
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
