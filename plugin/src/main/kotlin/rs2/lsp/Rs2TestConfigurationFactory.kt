package rs2.lsp

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class Rs2TestConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = Rs2TestRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        Rs2TestRunConfiguration(project, this, "RS2 Test")
}
