package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.lsp.api.LspServerManager

class Rs2StartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        Rs2LspBinaryManager.getOrExtractBinary()
        LspServerManager.getInstance(project)
            .stopAndRestartIfNeeded(Rs2LspServerSupportProvider::class.java)
    }
}
