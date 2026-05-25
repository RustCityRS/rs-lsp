package rs2.lsp

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement

class Rs2Annotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        val tokenType = node.elementType

        if (tokenType != Rs2TokenTypes.IDENTIFIER) return

        val text = element.text
        val project = element.project
        val commands = Rs2CommandRegistry.getCommands(project)

        if (text in commands) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .textAttributes(Rs2SyntaxHighlighter.COMMAND_KEY)
                .create()
            return
        }

        val entities = Rs2CommandRegistry.getEntities(project)
        if (text in entities) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .textAttributes(Rs2SyntaxHighlighter.ENTITY_KEY)
                .create()
            return
        }

        // Check for component references: "interface:component"
        val prev = findPrecedingIdentifier(element)
        if (prev != null && "$prev:$text" in entities) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .textAttributes(Rs2SyntaxHighlighter.ENTITY_KEY)
                .create()
        }
    }

    private fun findPrecedingIdentifier(element: PsiElement): String? {
        // Walk backwards: skip colon, find identifier
        var sibling = element.prevSibling
        if (sibling != null && sibling.text == ":") {
            sibling = sibling.prevSibling
            if (sibling != null && sibling.node?.elementType == Rs2TokenTypes.IDENTIFIER) {
                return sibling.text
            }
        }
        return null
    }
}
