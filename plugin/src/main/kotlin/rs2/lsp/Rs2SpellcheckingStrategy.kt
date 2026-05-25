package rs2.lsp

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer

class Rs2SpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        return EMPTY_TOKENIZER
    }

    override fun isMyContext(element: PsiElement): Boolean {
        return element.containingFile?.fileType == Rs2FileType.INSTANCE
    }
}
