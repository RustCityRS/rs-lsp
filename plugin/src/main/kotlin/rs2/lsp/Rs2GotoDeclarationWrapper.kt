package rs2.lsp

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.find.FindManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.pom.Navigatable
import com.intellij.psi.SingleRootFileViewProvider

class Rs2GotoDeclarationWrapper(private val original: AnAction) : GotoDeclarationAction() {
    init {
        // copyFrom() does a LOG.error on "ShortcutSet of global AnActions" which
        // THROWS in dev/internal IDEs (the sandbox) — aborting construction and
        // leaving the action un-replaced (so the large-file declaration->usages
        // path never installs). The keymap binds the shortcut to the action ID
        // regardless, so swallow it and keep the wrap.
        try {
            copyFrom(original)
        } catch (_: Throwable) {
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project

        // Large RS2Config dumps (past idea.max.intellisense.filesize) have no PSI,
        // so the normal pipeline can't resolve from the caret. Drive it off the
        // document instead. Small config files keep the standard PSI behaviour.
        if (project != null && editor != null && vFile != null &&
            vFile.fileType is Rs2ConfigFileType &&
            SingleRootFileViewProvider.isTooLargeForIntelligence(vFile)
        ) {
            val offset = editor.caretModel.offset
            val text = editor.document.charsSequence
            val decl = Rs2ConfigNav.declarationAtCaret(project, vFile, text, offset)
            if (decl != null) {
                FindManager.getInstance(project).findUsages(decl) // declaration → usages
                return
            }
            val ref = Rs2ConfigNav.resolveReference(project, text, offset)
            if (ref != null) {
                ref.navigate(true)
                return
            }
        }

        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
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
                    (target as? Navigatable)?.navigate(true)
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
