package rs2.lsp

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.util.IncorrectOperationException

/**
 * PSI for a config `[section]` header name. Modeling it as a
 * [PsiNameIdentifierOwner] makes IntelliJ treat it as a *declaration*, so both
 * Alt+F7 (Find Usages) and Ctrl-click ("Go to Declaration or Usages") work on
 * it — the search itself is provided by [Rs2ConfigFindUsagesProvider] /
 * [Rs2FindUsagesHandlerFactory].
 */
class Rs2ConfigSectionElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? =
        (node.findChildByType(Rs2ConfigTokenTypes.SECTION_NAME)
            ?: node.findChildByType(Rs2ConfigTokenTypes.PACK_NAME))?.psi

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException("Renaming config sections is not supported")
}
