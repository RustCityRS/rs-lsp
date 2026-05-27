package rs2.lsp

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.JPanel
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class Rs2InlayHintsProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("rs2.inlay.hints")
    override val name: String = "RS2 Parameter Hints"
    override val previewText: String = "~my_proc(1, \"hello\")"

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        return Rs2InlayHintsCollector(editor)
    }
}

@Suppress("UnstableApiUsage")
private class Rs2InlayHintsCollector(private val myEditor: Editor) : InlayHintsCollector {
    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (element != element.containingFile) return true
        if (element.containingFile.name == "engine.rs2") return false
        val project = element.project
        val text = element.containingFile.text
        val factory = PresentationFactory(myEditor)

        val cmdParamNames = Rs2CommandRegistry.getCommandParamNames(project)
        val cmdReturnTypes = Rs2CommandRegistry.getCommandReturnTypes(project)
        val scriptParamNames = Rs2CommandRegistry.getScriptParamNames(project)
        val scriptReturnTypes = Rs2CommandRegistry.getScriptReturnTypes(project)
        val commands = Rs2CommandRegistry.getCommands(project)
        val constantTypes = Rs2CommandRegistry.getConstantTypes(project)

        var i = 0
        while (i < text.length) {
            // Skip strings
            if (text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"' && text[i] != '\n') i++
                if (i < text.length) i++
                continue
            }
            // Skip comments
            if (text[i] == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            // Constants: ^name
            if (text[i] == '^' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                i++
                val wordStart = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val name = text.substring(wordStart, i)
                val type = constantTypes[name]
                if (type != null) {
                    sink.addInlineElement(i, false, factory.roundWithBackground(factory.smallText(": $type")), false)
                }
                continue
            }
            if (text[i].isLetter() || text[i] == '_') {
                val wordStart = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(wordStart, i)
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                if (j < text.length && text[j] == '(' && word in commands) {
                    val names = cmdParamNames[word]
                    if (names != null && names.isNotEmpty()) {
                        addParamHints(text, j, names, sink, factory)
                    }
                    val retTypes = cmdReturnTypes[word]
                    if (retTypes != null && retTypes.isNotEmpty()) {
                        val closeIdx = findClosingParen(text, j)
                        if (closeIdx >= 0) {
                            val label = retTypes.joinToString(", ")
                            sink.addInlineElement(closeIdx + 1, false, factory.roundWithBackground(factory.smallText("-> $label")), false)
                        }
                    }
                } else if (word in commands) {
                    val retTypes = cmdReturnTypes[word]
                    if (retTypes != null && retTypes.isNotEmpty()) {
                        val label = retTypes.joinToString(", ")
                        sink.addInlineElement(i, false, factory.roundWithBackground(factory.smallText("-> $label")), false)
                    }
                }
                continue
            }

            if (text[i] == '~' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                i++
                val wordStart = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(wordStart, i)
                var j = i
                while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
                if (j < text.length && text[j] == '(') {
                    val names = scriptParamNames[word]
                    if (names != null && names.isNotEmpty()) {
                        addParamHints(text, j, names, sink, factory)
                    }
                    val retTypes = scriptReturnTypes[word]
                    if (retTypes != null && retTypes.isNotEmpty()) {
                        val closeIdx = findClosingParen(text, j)
                        if (closeIdx >= 0) {
                            val label = retTypes.joinToString(", ")
                            sink.addInlineElement(closeIdx + 1, false, factory.roundWithBackground(factory.smallText("-> $label")), false)
                        }
                    }
                } else {
                    // No parens (zero-arg proc call like ~get_state)
                    val retTypes = scriptReturnTypes[word]
                    if (retTypes != null && retTypes.isNotEmpty()) {
                        val label = retTypes.joinToString(", ")
                        sink.addInlineElement(i, false, factory.roundWithBackground(factory.smallText("-> $label")), false)
                    }
                }
                continue
            }

            i++
        }
        return false
    }

    private fun addParamHints(text: String, openParen: Int, names: List<String>, sink: InlayHintsSink, factory: PresentationFactory) {
        var depth = 0
        var argIdx = 0
        var i = openParen
        while (i < text.length) {
            when (text[i]) {
                '(' -> {
                    depth++
                    if (depth == 1 && argIdx < names.size) {
                        val argStart = findNextNonSpace(text, i + 1)
                        if (argStart < text.length && text[argStart] != ')') {
                            sink.addInlineElement(argStart, false, factory.roundWithBackground(factory.smallText("${names[argIdx]}:")), false)
                        }
                    }
                }
                ')' -> { depth--; if (depth == 0) return }
                ',' -> {
                    if (depth == 1) {
                        argIdx++
                        if (argIdx < names.size) {
                            val argStart = findNextNonSpace(text, i + 1)
                            if (argStart < text.length) {
                                sink.addInlineElement(argStart, false, factory.roundWithBackground(factory.smallText("${names[argIdx]}:")), false)
                            }
                        }
                    }
                }
                '"' -> { i++; while (i < text.length && text[i] != '"' && text[i] != '\n') i++ }
            }
            i++
        }
    }

    private fun findClosingParen(text: String, openParen: Int): Int {
        var depth = 0
        var i = openParen
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
                '"' -> { i++; while (i < text.length && text[i] != '"' && text[i] != '\n') i++ }
            }
            i++
        }
        return -1
    }

    private fun findNextNonSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }
}
