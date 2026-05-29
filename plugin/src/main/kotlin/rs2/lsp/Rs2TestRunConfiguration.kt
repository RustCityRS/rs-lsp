package rs2.lsp

import com.intellij.execution.Executor
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.runners.ExecutionEnvironment
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
    var testFile: String = ""
    var testDir: String = ""
    var contentDir: String = "content"

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

            if (testFile.isNotBlank()) {
                cmd.add("--file")
                cmd.add(testFile)
            }

            if (testDir.isNotBlank()) {
                cmd.add("--dir")
                cmd.add(testDir)
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
        element.setAttribute("testFile", testFile)
        element.setAttribute("testDir", testDir)
        element.setAttribute("contentDir", contentDir)
    }

    override fun readExternal(element: org.jdom.Element) {
        super.readExternal(element)
        testFilter = element.getAttributeValue("testFilter") ?: ""
        testFile = element.getAttributeValue("testFile") ?: ""
        testDir = element.getAttributeValue("testDir") ?: ""
        contentDir = element.getAttributeValue("contentDir") ?: "content"
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
    private val fileField = JTextField()
    private val dirField = JTextField()
    private val contentDirField = JTextField()

    override fun createEditor(): JComponent {
        val panel = JPanel(BorderLayout())
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(LabeledComponent.create(contentDirField, "Content directory"))
            add(LabeledComponent.create(filterField, "Test filter"))
            add(LabeledComponent.create(fileField, "Test file"))
            add(LabeledComponent.create(dirField, "Test directory"))
        }
        panel.add(inner, BorderLayout.NORTH)
        return panel
    }

    override fun resetEditorFrom(config: Rs2TestRunConfiguration) {
        filterField.text = config.testFilter
        fileField.text = config.testFile
        dirField.text = config.testDir
        contentDirField.text = config.contentDir
    }

    override fun applyEditorTo(config: Rs2TestRunConfiguration) {
        config.testFilter = filterField.text
        config.testFile = fileField.text
        config.testDir = dirField.text
        config.contentDir = contentDirField.text
    }
}
