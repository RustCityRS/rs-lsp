package rs2.lsp

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement

class Rs2TestLineMarkerProvider : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        if (element.node?.elementType != Rs2TokenTypes.TRIGGER_BRACKET) return null
        if (element.text != "[") return null
        if (!isLineStart(element)) return null

        val line = collectBracketedHeader(element) ?: return null
        val labelName = extractLabelName(line) ?: return null

        if (!isBelowTestScript(element)) return null

        val tooltip = "Run test '$labelName'"

        @Suppress("DEPRECATION")
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            { tooltip },
            *ExecutorAction.getActions(0)
        )
    }

    companion object {
        fun extractLabelName(header: String): String? {
            if (!header.startsWith("[label,")) return null
            val rest = header.removePrefix("[label,")
            val end = rest.indexOf(']')
            if (end < 0) return null
            return rest.substring(0, end)
        }

        fun isBelowTestScript(element: PsiElement): Boolean {
            val file = element.containingFile ?: return false
            val text = file.text
            val markerPos = text.indexOf("\n#testscript")
            if (markerPos < 0) {
                return text.startsWith("#testscript")
            }
            return element.textOffset > markerPos
        }

        private fun isLineStart(element: PsiElement): Boolean {
            val doc = element.containingFile?.viewProvider?.document ?: return false
            val offset = element.textOffset
            val lineNum = doc.getLineNumber(offset)
            val lineStart = doc.getLineStartOffset(lineNum)
            val textBefore = doc.getText(com.intellij.openapi.util.TextRange(lineStart, offset)).trim()
            return textBefore.isEmpty()
        }

        private fun collectBracketedHeader(lbracket: PsiElement): String? {
            val sb = StringBuilder()
            var node: PsiElement? = lbracket
            while (node != null) {
                sb.append(node.text)
                if (node.text == "]") break
                node = node.nextSibling
            }
            val header = sb.toString()
            if (!header.startsWith("[") || !header.endsWith("]")) return null
            return header
        }
    }
}
