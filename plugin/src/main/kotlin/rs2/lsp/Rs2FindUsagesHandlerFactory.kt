package rs2.lsp

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

class Rs2FindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean {
        if (element is Rs2ConfigDeclTarget) return true
        return element.node?.elementType in FIND_USABLE_TYPES
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        Rs2IndexFindUsagesHandler(element)

    private companion object {
        // Find Usages works from both declarations and references (any identifier
        // naming an entity/script/const/var), so Alt+F7 on a use resolves the same
        // set as Alt+F7 on the declaration.
        val FIND_USABLE_TYPES = setOf(
            Rs2TokenTypes.TRIGGER_SCRIPT_NAME,
            Rs2TokenTypes.TRIGGER_SUBJECT,
            Rs2TokenTypes.PROC_CALL,
            Rs2TokenTypes.JUMP_CALL,
            Rs2TokenTypes.IDENTIFIER,
            Rs2TokenTypes.CONSTANT,
            Rs2TokenTypes.GAME_VAR,
            Rs2ConfigTokenTypes.SECTION,
            Rs2ConfigTokenTypes.SECTION_NAME,
            Rs2ConfigTokenTypes.PACK_NAME,
            Rs2ConfigTokenTypes.IDENTIFIER,
            Rs2ConfigTokenTypes.CONSTANT,
            Rs2ConfigTokenTypes.GAME_VAR,
        )
    }
}

/**
 * Find Usages backed by [Rs2ReferenceIndex]: a single index lookup returns every
 * `(file, offset)` occurrence of the name across the project's content/ tree —
 * scripts, config files, pack entries, and the large `all.*` dumps — including the
 * declaration line(s). No per-invocation directory scan or dump re-reads, and
 * `ref/` is excluded because the index is content/-scoped.
 */
private class Rs2IndexFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        // Everything — including the index query — must run inside a read action;
        // FileBasedIndex.processValues requires read access, so calling find()
        // outside one comes back empty (and reports "no usages").
        return runReadAction {
            val project = psiElement.project
            val name = psiElement.text.trimStart('~', '@', '^', '%', '$')
            if (name.isBlank()) return@runReadAction true

            val psiManager = PsiManager.getInstance(project)
            for ((file, offset) in Rs2ReferenceIndex.find(project, name)) {
                ProgressManager.checkCanceled()
                if (!file.isValid) continue
                val psiFile = psiManager.findFile(file) ?: continue
                if (!processor.process(UsageInfo(psiFile, offset, offset + name.length))) {
                    return@runReadAction false
                }
            }
            true
        }
    }
}
