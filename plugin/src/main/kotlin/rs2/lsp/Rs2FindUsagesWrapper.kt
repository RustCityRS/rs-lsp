package rs2.lsp

import com.intellij.find.FindManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.SingleRootFileViewProvider

/**
 * Makes Find Usages work from *inside* a large RS2Config dump (e.g. `all.npc`).
 * Such files exceed idea.max.intellisense.filesize, so they have no PSI token at
 * the caret and the normal pipeline can't recognize the `[name]` declaration. This
 * wrapper reads the caret declaration/reference straight from the document and runs
 * the search on an index-backed [Rs2ConfigDeclTarget]. Small files (which keep PSI)
 * fall through to the standard handler.
 */
class Rs2FindUsagesWrapper(private val original: AnAction) : AnAction() {
    init {
        // copyFrom() does a LOG.error on "ShortcutSet of global AnActions" which
        // THROWS in dev/internal IDEs (the sandbox) — which would abort the wrapper
        // construction and leave the action un-replaced. The keymap binds the
        // shortcut to the action ID regardless, so swallow it and keep the wrap.
        try {
            copyFrom(original)
        } catch (_: Throwable) {
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project

        if (project != null && editor != null && vFile != null &&
            vFile.fileType is Rs2ConfigFileType &&
            SingleRootFileViewProvider.isTooLargeForIntelligence(vFile)
        ) {
            val offset = editor.caretModel.offset
            val text = editor.document.charsSequence
            val target = Rs2ConfigNav.declarationAtCaret(project, vFile, text, offset)
                ?: Rs2ConfigNav.resolveReference(project, text, offset)
            if (target != null) {
                FindManager.getInstance(project).findUsages(target)
                return
            }
        }

        original.actionPerformed(e)
    }

    override fun update(e: AnActionEvent) {
        original.update(e)
    }

    override fun getActionUpdateThread() = original.actionUpdateThread
}
