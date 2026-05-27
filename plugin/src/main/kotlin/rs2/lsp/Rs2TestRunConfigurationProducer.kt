package rs2.lsp

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

class Rs2TestRunConfigurationProducer : LazyRunConfigurationProducer<Rs2TestRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory {
        val type = ConfigurationTypeUtil.findConfigurationType(Rs2TestRunConfigurationType::class.java)
        return type.configurationFactories[0]
    }

    override fun isConfigurationFromContext(
        configuration: Rs2TestRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val testName = findTestLabelAt(context) ?: return false
        return configuration.testFilter == testName
    }

    override fun setupConfigurationFromContext(
        configuration: Rs2TestRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val element = context.psiLocation ?: return false
        val file = element.containingFile ?: return false
        if (file.virtualFile?.extension != "rs2") return false

        val testName = findTestLabelAt(context)
        if (testName != null) {
            configuration.testFilter = testName
            configuration.name = "RS2: $testName"
            return true
        }

        return false
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
}
