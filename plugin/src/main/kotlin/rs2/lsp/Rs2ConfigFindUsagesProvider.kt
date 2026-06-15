package rs2.lsp

import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement

/**
 * Enables Find Usages on a config-file section header (e.g. `[inv_83]`). The
 * actual search is performed by [Rs2FindUsagesHandlerFactory]; this provider
 * just tells the platform the element is searchable and how to describe it.
 */
class Rs2ConfigFindUsagesProvider : FindUsagesProvider {
    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        val t = psiElement.node?.elementType
        return t == Rs2ConfigTokenTypes.SECTION ||
                t == Rs2ConfigTokenTypes.SECTION_NAME ||
                t == Rs2ConfigTokenTypes.PACK_NAME
    }

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String = "config entity"

    override fun getDescriptiveName(element: PsiElement): String = element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text

    override fun getWordsScanner(): WordsScanner? = null
}
