package rs2.lsp

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

class Rs2Annotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        val tokenType = node.elementType

        if (tokenType == Rs2TokenTypes.STRING) {
            annotateStringInterpolation(element, holder)
            return
        }

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

        // Check for compound entity names: "interface:component" or "cheese+tom_batta"
        val fullName = buildFullName(element)
        if (fullName != text && fullName in entities) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .textAttributes(Rs2SyntaxHighlighter.ENTITY_KEY)
                .create()
        }
    }

    private fun enforced(holder: AnnotationHolder, range: TextRange, key: TextAttributesKey) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val attrs = scheme.getAttributes(key)?.clone() ?: TextAttributes()
        if (attrs.foregroundColor == null) {
            attrs.foregroundColor = scheme.defaultForeground
        }
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .enforcedTextAttributes(attrs)
            .create()
    }

    private fun annotateStringInterpolation(element: PsiElement, holder: AnnotationHolder) {
        val text = element.text
        val baseOffset = element.textRange.startOffset
        val commands = Rs2CommandRegistry.getCommands(element.project)
        var i = 0
        while (i < text.length) {
            if (text[i] == '<') {
                val closeIdx = text.indexOf('>', i)
                if (closeIdx < 0) break
                val inner = text.substring(i + 1, closeIdx)

                enforced(holder, TextRange(baseOffset + i, baseOffset + i + 1), Rs2SyntaxHighlighter.KEYWORD_KEY)
                enforced(holder, TextRange(baseOffset + closeIdx, baseOffset + closeIdx + 1), Rs2SyntaxHighlighter.KEYWORD_KEY)

                val parenIdx = inner.indexOf('(')
                val cmdName = if (parenIdx >= 0) inner.substring(0, parenIdx).trim() else inner.trim()

                if (cmdName.contains(',')) {
                    enforced(holder, TextRange(baseOffset + i + 1, baseOffset + closeIdx), Rs2SyntaxHighlighter.KEYWORD_KEY)
                } else if (cmdName in commands) {
                    enforced(holder, TextRange(baseOffset + i + 1, baseOffset + i + 1 + cmdName.length), Rs2SyntaxHighlighter.COMMAND_KEY)
                    if (parenIdx >= 0) {
                        val argsStart = i + 1 + parenIdx
                        enforced(holder, TextRange(baseOffset + argsStart, baseOffset + argsStart + 1), Rs2SyntaxHighlighter.PAREN_KEY)
                        val closeParenOffset = inner.indexOf(')', parenIdx)
                        if (closeParenOffset >= 0) {
                            enforced(holder, TextRange(baseOffset + i + 1 + closeParenOffset, baseOffset + i + 1 + closeParenOffset + 1), Rs2SyntaxHighlighter.PAREN_KEY)
                        }
                        val argsStr = inner.substring(parenIdx)
                        var j = 0
                        while (j < argsStr.length) {
                            if (argsStr[j] == '$') {
                                val varStart = j
                                j++
                                while (j < argsStr.length && (argsStr[j].isLetterOrDigit() || argsStr[j] == '_')) j++
                                enforced(holder, TextRange(baseOffset + argsStart + varStart, baseOffset + argsStart + j), Rs2SyntaxHighlighter.LOCAL_VAR_KEY)
                            } else if (argsStr[j] == ',') {
                                enforced(holder, TextRange(baseOffset + argsStart + j, baseOffset + argsStart + j + 1), Rs2SyntaxHighlighter.COMMA_KEY)
                                j++
                            } else if (argsStr[j].isLetter() || argsStr[j] == '_') {
                                val wordStart = j
                                while (j < argsStr.length && (argsStr[j].isLetterOrDigit() || argsStr[j] == '_')) j++
                                val argWord = argsStr.substring(wordStart, j)
                                val entities = Rs2CommandRegistry.getEntities(element.project)
                                if (argWord in commands) {
                                    enforced(holder, TextRange(baseOffset + argsStart + wordStart, baseOffset + argsStart + j), Rs2SyntaxHighlighter.COMMAND_KEY)
                                } else if (argWord in entities) {
                                    enforced(holder, TextRange(baseOffset + argsStart + wordStart, baseOffset + argsStart + j), Rs2SyntaxHighlighter.ENTITY_KEY)
                                }
                            } else {
                                j++
                            }
                        }
                    }
                } else {
                    enforced(holder, TextRange(baseOffset + i + 1, baseOffset + closeIdx), Rs2SyntaxHighlighter.KEYWORD_KEY)
                }

                i = closeIdx + 1
            } else {
                i++
            }
        }
    }

    private fun buildFullName(element: PsiElement): String {
        val sb = StringBuilder(element.text)
        // Expand left across : or + separators
        var left = element.prevSibling
        while (left != null && (left.text == ":" || left.text == "+")) {
            val sep = left.text
            left = left.prevSibling
            if (left != null && left.node?.elementType == Rs2TokenTypes.IDENTIFIER) {
                sb.insert(0, left.text + sep)
                left = left.prevSibling
            } else {
                break
            }
        }
        // Expand right across : or + separators
        var right = element.nextSibling
        while (right != null && (right.text == ":" || right.text == "+")) {
            val sep = right.text
            right = right.nextSibling
            if (right != null && right.node?.elementType == Rs2TokenTypes.IDENTIFIER) {
                sb.append(sep + right.text)
                right = right.nextSibling
            } else {
                break
            }
        }
        return sb.toString()
    }
}
