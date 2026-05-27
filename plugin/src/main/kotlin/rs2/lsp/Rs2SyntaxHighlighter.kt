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

class Rs2SyntaxHighlighter : SyntaxHighlighterBase() {

    companion object {
        // Keywords: orange (already works via KEYWORD fallback)
        val KEYWORD_KEY = createTextAttributesKey("RS2_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val BOOL_KEY = createTextAttributesKey("RS2_BOOL", DefaultLanguageHighlighterColors.KEYWORD)
        val NULL_KEY = createTextAttributesKey("RS2_NULL", DefaultLanguageHighlighterColors.KEYWORD)

        // Types: cyan (like Rust struct/type names)
        val TYPE_KEY = createTextAttributesKey("RS2_TYPE", DefaultLanguageHighlighterColors.KEYWORD)

        // Proc/jump calls: blue (like Rust macros)
        val PROC_CALL_KEY = createTextAttributesKey("RS2_PROC_CALL", DefaultLanguageHighlighterColors.KEYWORD)
        val JUMP_CALL_KEY = createTextAttributesKey("RS2_JUMP_CALL", DefaultLanguageHighlighterColors.KEYWORD)

        // Commands: cyan (like Rust struct names)
        val COMMAND_KEY = createTextAttributesKey("RS2_COMMAND", DefaultLanguageHighlighterColors.IDENTIFIER)

        // Variables
        val LOCAL_VAR_KEY = createTextAttributesKey("RS2_LOCAL_VAR", DefaultLanguageHighlighterColors.IDENTIFIER)
        val GAME_VAR_KEY = createTextAttributesKey("RS2_GAME_VAR", DefaultLanguageHighlighterColors.IDENTIFIER)
        val CONSTANT_KEY = createTextAttributesKey("RS2_CONSTANT", DefaultLanguageHighlighterColors.KEYWORD)

        // Entity references from .pack files (obj, npc, loc, inv names, etc.)
        val ENTITY_KEY = createTextAttributesKey("RS2_ENTITY", DefaultLanguageHighlighterColors.IDENTIFIER)

        // Trigger headers: yellow (METADATA works)
        val TRIGGER_BRACKET_KEY = createTextAttributesKey("RS2_TRIGGER_BRACKET", DefaultLanguageHighlighterColors.METADATA)
        val TRIGGER_TYPE_KEY = createTextAttributesKey("RS2_TRIGGER_TYPE", DefaultLanguageHighlighterColors.METADATA)
        val TRIGGER_SUBJECT_KEY = createTextAttributesKey("RS2_TRIGGER_SUBJECT", DefaultLanguageHighlighterColors.NUMBER)
        val TRIGGER_SCRIPT_NAME_KEY = createTextAttributesKey("RS2_TRIGGER_SCRIPT_NAME", DefaultLanguageHighlighterColors.NUMBER)

        // Literals
        val STRING_KEY = createTextAttributesKey("RS2_STRING", DefaultLanguageHighlighterColors.STRING)
        val NUMBER_KEY = createTextAttributesKey("RS2_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val COORD_KEY = createTextAttributesKey("RS2_COORD", DefaultLanguageHighlighterColors.NUMBER)

        // Identifiers
        val IDENTIFIER_KEY = createTextAttributesKey("RS2_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)

        // Comments
        val COMMENT_KEY = createTextAttributesKey("RS2_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)

        // Punctuation
        val OPERATOR_KEY = createTextAttributesKey("RS2_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val PAREN_KEY = createTextAttributesKey("RS2_PAREN", DefaultLanguageHighlighterColors.PARENTHESES)
        val BRACE_KEY = createTextAttributesKey("RS2_BRACE", DefaultLanguageHighlighterColors.BRACES)
        val COMMA_KEY = createTextAttributesKey("RS2_COMMA", DefaultLanguageHighlighterColors.COMMA)
        val SEMICOLON_KEY = createTextAttributesKey("RS2_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
        val DOT_KEY = createTextAttributesKey("RS2_DOT", DefaultLanguageHighlighterColors.DOT)

    }

    override fun getHighlightingLexer(): Lexer = Rs2Lexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return when (tokenType) {
            Rs2TokenTypes.COMMENT -> arrayOf(COMMENT_KEY)
            Rs2TokenTypes.STRING -> arrayOf(STRING_KEY)
            Rs2TokenTypes.NUMBER -> arrayOf(NUMBER_KEY)
            Rs2TokenTypes.COORD_LITERAL -> arrayOf(COORD_KEY)
            Rs2TokenTypes.KEYWORD -> arrayOf(KEYWORD_KEY)
            Rs2TokenTypes.TYPE_NAME -> arrayOf(TYPE_KEY)
            Rs2TokenTypes.TRUE, Rs2TokenTypes.FALSE -> arrayOf(BOOL_KEY)
            Rs2TokenTypes.NULL -> arrayOf(NULL_KEY)
            Rs2TokenTypes.LOCAL_VAR -> arrayOf(LOCAL_VAR_KEY)
            Rs2TokenTypes.GAME_VAR -> arrayOf(GAME_VAR_KEY)
            Rs2TokenTypes.CONSTANT -> arrayOf(CONSTANT_KEY)
            Rs2TokenTypes.PROC_CALL -> arrayOf(PROC_CALL_KEY)
            Rs2TokenTypes.JUMP_CALL -> arrayOf(JUMP_CALL_KEY)
            Rs2TokenTypes.COMMAND -> arrayOf(COMMAND_KEY)
            Rs2TokenTypes.IDENTIFIER -> arrayOf(IDENTIFIER_KEY)
            Rs2TokenTypes.TRIGGER_BRACKET -> arrayOf(TRIGGER_BRACKET_KEY)
            Rs2TokenTypes.TRIGGER_TYPE -> arrayOf(TYPE_KEY)
            Rs2TokenTypes.TRIGGER_SUBJECT -> arrayOf(TRIGGER_SUBJECT_KEY)
            Rs2TokenTypes.TRIGGER_SCRIPT_NAME -> arrayOf(TRIGGER_SCRIPT_NAME_KEY)
            Rs2TokenTypes.OPERATOR -> arrayOf(OPERATOR_KEY)
            Rs2TokenTypes.PAREN -> arrayOf(PAREN_KEY)
            Rs2TokenTypes.BRACE -> arrayOf(BRACE_KEY)
            Rs2TokenTypes.COMMA -> arrayOf(COMMA_KEY)
            Rs2TokenTypes.SEMICOLON -> arrayOf(SEMICOLON_KEY)
            Rs2TokenTypes.DOT -> arrayOf(DOT_KEY)
            else -> TextAttributesKey.EMPTY_ARRAY
        }
    }
}

class Rs2SyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return Rs2SyntaxHighlighter()
    }
}
