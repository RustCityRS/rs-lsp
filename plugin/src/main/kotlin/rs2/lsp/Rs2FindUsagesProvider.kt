package rs2.lsp

import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement

class Rs2FindUsagesProvider : FindUsagesProvider {
    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        val tokenType = psiElement.node?.elementType ?: return false
        return tokenType == Rs2TokenTypes.TRIGGER_SCRIPT_NAME ||
                tokenType == Rs2TokenTypes.PROC_CALL ||
                tokenType == Rs2TokenTypes.JUMP_CALL
    }

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String {
        return when (element.node?.elementType) {
            Rs2TokenTypes.TRIGGER_SCRIPT_NAME -> "script"
            Rs2TokenTypes.PROC_CALL -> "proc"
            Rs2TokenTypes.JUMP_CALL -> "label"
            else -> ""
        }
    }

    override fun getDescriptiveName(element: PsiElement): String {
        return element.text.trimStart('~', '@')
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        return element.text
    }

    override fun getWordsScanner(): WordsScanner? = null
}
