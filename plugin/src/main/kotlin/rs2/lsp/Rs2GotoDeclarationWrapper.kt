package rs2.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiElement

class Rs2GotoDeclarationWrapper(private val original: AnAction) : GotoDeclarationAction() {
    init {
        copyFrom(original)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)

        if (psiFile?.language == Rs2Language && editor != null) {
            val element = psiFile.findElementAt(editor.caretModel.offset)
            if (element?.node?.elementType == Rs2TokenTypes.TRIGGER_SCRIPT_NAME) {
                ActionManager.getInstance().getAction("ShowUsages")?.actionPerformed(e)
                return
            }
            // String interpolation: navigate to command/var inside <> in strings
            if (element?.node?.elementType == Rs2TokenTypes.STRING) {
                val handler = Rs2GotoDeclarationHandler()
                val target = handler.findStringInterpolationTarget(element, editor.caretModel.offset, element.project)
                if (target != null) {
                    (target as? com.intellij.pom.Navigatable)?.navigate(true)
                    return
                }
            }
        }
        original.actionPerformed(e)
    }

    override fun update(e: AnActionEvent) {
        original.update(e)
    }

    override fun getActionUpdateThread() = original.actionUpdateThread
}
