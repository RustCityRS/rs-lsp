package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class Rs2StartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        Rs2LspBinaryManager.checkForUpdatesAsync()
    }
}
