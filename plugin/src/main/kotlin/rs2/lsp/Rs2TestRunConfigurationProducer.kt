package rs2.lsp

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile

class Rs2TestRunConfigurationProducer : LazyRunConfigurationProducer<Rs2TestRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory {
        val type = ConfigurationTypeUtil.findConfigurationType(Rs2TestRunConfigurationType::class.java)
        return type.configurationFactories[0]
    }

    override fun isConfigurationFromContext(
        configuration: Rs2TestRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val element = context.psiLocation ?: return false

        // Directory context
        val dir = getDirFromContext(context)
        if (dir != null) {
            return configuration.testDir.isNotBlank() &&
                    dir.virtualFile.path == configuration.testDir
        }

        val file = element.containingFile ?: return false
        if (file.virtualFile?.extension != "rs2") return false
        if (!fileHasTestScript(file)) return false

        // Single test label context
        val testName = findTestLabelAt(context)
        if (testName != null) {
            return configuration.testFilter == testName
        }

        // File context (not on a specific label)
        return configuration.testFile.isNotBlank() &&
                file.virtualFile?.path == configuration.testFile
    }

    override fun setupConfigurationFromContext(
        configuration: Rs2TestRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        // Directory context
        val dir = getDirFromContext(context)
        if (dir != null && dirHasTestScripts(dir)) {
            configuration.testDir = dir.virtualFile.path
            configuration.name = "RS2 Tests: ${dir.name}/"
            return true
        }

        val element = context.psiLocation ?: return false
        val file = element.containingFile ?: return false
        if (file.virtualFile?.extension != "rs2") return false
        if (!fileHasTestScript(file)) return false

        // Single test label
        val testName = findTestLabelAt(context)
        if (testName != null) {
            configuration.testFilter = testName
            configuration.name = "RS2: $testName"
            return true
        }

        // Whole file
        configuration.testFile = file.virtualFile.path
        configuration.name = "RS2 Tests: ${file.virtualFile.name}"
        return true
    }

    private fun findTestLabelAt(context: ConfigurationContext): String? {
        val element = context.psiLocation ?: return null
        val file = element.containingFile ?: return null

        if (!Rs2TestLineMarkerProvider.isBelowTestScript(element)) return null

        val doc = file.viewProvider.document ?: return null
        val lineNum = doc.getLineNumber(element.textOffset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineEnd = doc.getLineEndOffset(lineNum)
        val lineText = doc.getText(TextRange(lineStart, lineEnd)).trim()

        return Rs2TestLineMarkerProvider.extractLabelName(lineText)
    }

    private fun getDirFromContext(context: ConfigurationContext): PsiDirectory? {
        val location = context.location ?: return null
        val psi = location.psiElement
        if (psi is PsiDirectory) return psi
        return null
    }

    private fun fileHasTestScript(file: PsiFile): Boolean {
        val text = file.text
        return text.startsWith("#testscript") || text.contains("\n#testscript")
    }

    private fun dirHasTestScripts(dir: PsiDirectory): Boolean {
        return dir.files.any { f ->
            f.virtualFile.extension == "rs2" && fileHasTestScript(f)
        } || dir.subdirectories.any { dirHasTestScripts(it) }
    }
}
