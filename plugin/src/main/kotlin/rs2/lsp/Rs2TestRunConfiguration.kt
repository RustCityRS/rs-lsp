package rs2.lsp

import com.intellij.execution.Executor
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.util.SystemInfo
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import java.awt.BorderLayout

class Rs2TestRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {

    var testFilter: String = ""
    var contentDir: String = "content"
    var filePath: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        Rs2TestSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return RunProfileState { _, _ ->
            val basePath = project.basePath ?: "."

            val cmd = mutableListOf<String>()
            val binary = findRsTestBinary(basePath)
            if (binary.startsWith("cargo")) {
                cmd.addAll(binary.split(" "))
            } else {
                cmd.add(binary)
            }
            cmd.add(contentDir)

            if (testFilter.isNotBlank()) {
                cmd.add("--filter")
                cmd.add(testFilter)
            }

            val commandLine = GeneralCommandLine(cmd)
                .withWorkDirectory(basePath)
                .withEnvironment("RUST_LOG", "rs_test=info,runec=warn")

            val handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
            val console = com.intellij.execution.filters.TextConsoleBuilderFactory
                .getInstance()
                .createBuilder(project)
                .console
            console.attachToProcess(handler)
            DefaultExecutionResult(console, handler)
        }
    }

    override fun writeExternal(element: org.jdom.Element) {
        super.writeExternal(element)
        element.setAttribute("testFilter", testFilter)
        element.setAttribute("contentDir", contentDir)
        element.setAttribute("filePath", filePath)
    }

    override fun readExternal(element: org.jdom.Element) {
        super.readExternal(element)
        testFilter = element.getAttributeValue("testFilter") ?: ""
        contentDir = element.getAttributeValue("contentDir") ?: "content"
        filePath = element.getAttributeValue("filePath") ?: ""
    }

    private fun findRsTestBinary(basePath: String): String {
        val ext = if (SystemInfo.isWindows) ".exe" else ""
        val candidates = listOf(
            "$basePath/target/debug/rs-test$ext",
            "$basePath/target/release/rs-test$ext",
        )
        for (candidate in candidates) {
            if (java.io.File(candidate).exists()) return candidate
        }
        return "cargo run -p rs-test --"
    }
}

class Rs2TestSettingsEditor : SettingsEditor<Rs2TestRunConfiguration>() {
    private val filterField = JTextField()
    private val contentDirField = JTextField()

    override fun createEditor(): JComponent {
        val panel = JPanel(BorderLayout())
        val filterComponent = LabeledComponent.create(filterField, "Test filter")
        val contentComponent = LabeledComponent.create(contentDirField, "Content directory")
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(contentComponent)
            add(filterComponent)
        }
        panel.add(inner, BorderLayout.NORTH)
        return panel
    }

    override fun resetEditorFrom(config: Rs2TestRunConfiguration) {
        filterField.text = config.testFilter
        contentDirField.text = config.contentDir
    }

    override fun applyEditorTo(config: Rs2TestRunConfiguration) {
        config.testFilter = filterField.text
        config.contentDir = contentDirField.text
    }
}
