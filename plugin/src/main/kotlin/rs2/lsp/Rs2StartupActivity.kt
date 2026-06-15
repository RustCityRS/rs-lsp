package rs2.lsp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspServerManager

class Rs2StartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        Rs2LspBinaryManager.getOrExtractBinary()
        LspServerManager.getInstance(project)
            .stopAndRestartIfNeeded(Rs2LspServerSupportProvider::class.java)

        ApplicationManager.getApplication().invokeLater {
            // replaceAction mutates the action registry; the modern platform
            // requires an explicit write-intent read action on the EDT.
            WriteIntentReadAction.run {
                val am = ActionManager.getInstance()
                val original = am.getAction("GotoDeclaration")
                if (original != null && original !is Rs2GotoDeclarationWrapper) {
                    am.replaceAction("GotoDeclaration", Rs2GotoDeclarationWrapper(original))
                }
            }
        }
    }
}
