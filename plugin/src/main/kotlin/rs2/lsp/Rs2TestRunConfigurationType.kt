package rs2.lsp

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.icons.AllIcons
import javax.swing.Icon

class Rs2TestRunConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "RS2 Test"
    override fun getConfigurationTypeDescription(): String = "Run RS2 script tests"
    override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run
    override fun getId(): String = ID
    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(Rs2TestConfigurationFactory(this))

    companion object {
        const val ID = "Rs2TestRunConfiguration"
    }
}
