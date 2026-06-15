package rs2.lsp

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class Rs2ConfigSyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        // Property name on the LHS of `key=value` — distinct from value identifiers.
        val KEY_KEY = createTextAttributesKey("RS2CFG_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        // Remaining concepts reuse the .rs2 color keys so both languages share one
        // configurable colour scheme (see colorSchemes/Rs2Default.xml).
    }

    override fun getHighlightingLexer(): Lexer = Rs2ConfigLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when (tokenType) {
            Rs2ConfigTokenTypes.COMMENT -> arrayOf(Rs2SyntaxHighlighter.COMMENT_KEY)
            Rs2ConfigTokenTypes.SECTION_BRACKET -> arrayOf(Rs2SyntaxHighlighter.TRIGGER_BRACKET_KEY)
            // Section name (e.g. `inv_83`) — same colour as a .rs2 script
            // declaration name (`[proc,foo]` → foo), per request.
            Rs2ConfigTokenTypes.SECTION_NAME -> arrayOf(Rs2SyntaxHighlighter.TRIGGER_SCRIPT_NAME_KEY)
            Rs2ConfigTokenTypes.KEY -> arrayOf(KEY_KEY)
            Rs2ConfigTokenTypes.EQUALS -> arrayOf(Rs2SyntaxHighlighter.OPERATOR_KEY)
            Rs2ConfigTokenTypes.NUMBER -> arrayOf(Rs2SyntaxHighlighter.NUMBER_KEY)
            Rs2ConfigTokenTypes.COORD -> arrayOf(Rs2SyntaxHighlighter.COORD_KEY)
            Rs2ConfigTokenTypes.STRING -> arrayOf(Rs2SyntaxHighlighter.STRING_KEY)
            Rs2ConfigTokenTypes.BOOL -> arrayOf(Rs2SyntaxHighlighter.BOOL_KEY)
            Rs2ConfigTokenTypes.CONSTANT -> arrayOf(Rs2SyntaxHighlighter.CONSTANT_KEY)
            Rs2ConfigTokenTypes.GAME_VAR -> arrayOf(Rs2SyntaxHighlighter.GAME_VAR_KEY)
            Rs2ConfigTokenTypes.IDENTIFIER -> arrayOf(Rs2SyntaxHighlighter.ENTITY_KEY)
            Rs2ConfigTokenTypes.PACK_NAME -> arrayOf(Rs2SyntaxHighlighter.ENTITY_KEY)
            Rs2ConfigTokenTypes.COMMA -> arrayOf(Rs2SyntaxHighlighter.COMMA_KEY)
            Rs2ConfigTokenTypes.OPERATOR -> arrayOf(Rs2SyntaxHighlighter.OPERATOR_KEY)
            else -> TextAttributesKey.EMPTY_ARRAY
        }
    }
}

class Rs2ConfigSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return Rs2ConfigSyntaxHighlighter()
    }
}
