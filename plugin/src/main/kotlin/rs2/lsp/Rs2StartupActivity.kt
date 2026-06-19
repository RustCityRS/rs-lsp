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
        Rs2LspBinaryManager.registerUninstallCleanup()
        LspServerManager.getInstance(project)
            .stopAndRestartIfNeeded(Rs2LspServerSupportProvider::class.java)

        ApplicationManager.getApplication().invokeLater {
            // replaceAction mutates the action registry; the modern platform
            // requires an explicit write-intent read action on the EDT.
            WriteIntentReadAction.run {
                val am = ActionManager.getInstance()
                val gotoDecl = am.getAction("GotoDeclaration")
                if (gotoDecl != null && gotoDecl !is Rs2GotoDeclarationWrapper) {
                    am.replaceAction("GotoDeclaration", Rs2GotoDeclarationWrapper(gotoDecl))
                }
                // Lets Find Usages / Show Usages work from inside large RS2Config
                // dumps (no PSI at the caret). Covers Alt+F7 and Ctrl+Alt+F7.
                for (id in listOf("FindUsages", "ShowUsages")) {
                    val action = am.getAction(id)
                    if (action != null && action !is Rs2FindUsagesWrapper) {
                        am.replaceAction(id, Rs2FindUsagesWrapper(action))
                    }
                }
            }
        }
    }
}
