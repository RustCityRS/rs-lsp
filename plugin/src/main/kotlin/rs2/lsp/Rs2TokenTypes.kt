package rs2.lsp

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class Rs2TokenType(debugName: String) : IElementType(debugName, Rs2Language)
class Rs2ElementType(debugName: String) : IElementType(debugName, Rs2Language)

object Rs2TokenTypes {
    // Literals
    @JvmField val NUMBER = Rs2TokenType("NUMBER")
    @JvmField val STRING = Rs2TokenType("STRING")
    @JvmField val COORD_LITERAL = Rs2TokenType("COORD_LITERAL")

    // Identifiers & variables
    @JvmField val IDENTIFIER = Rs2TokenType("IDENTIFIER")
    @JvmField val LOCAL_VAR = Rs2TokenType("LOCAL_VAR")       // $var
    @JvmField val GAME_VAR = Rs2TokenType("GAME_VAR")         // %var
    @JvmField val CONSTANT = Rs2TokenType("CONSTANT")         // ^const

    // Keywords
    @JvmField val KEYWORD = Rs2TokenType("KEYWORD")           // if, else, while, return, etc.
    @JvmField val TYPE_NAME = Rs2TokenType("TYPE_NAME")       // def_int, def_coord, etc.
    @JvmField val TRUE = Rs2TokenType("TRUE")
    @JvmField val FALSE = Rs2TokenType("FALSE")
    @JvmField val NULL = Rs2TokenType("NULL")

    // Calls
    @JvmField val PROC_CALL = Rs2TokenType("PROC_CALL")       // ~proc
    @JvmField val JUMP_CALL = Rs2TokenType("JUMP_CALL")       // @label
    @JvmField val COMMAND = Rs2TokenType("COMMAND")           // command_name(

    // Trigger header
    @JvmField val TRIGGER_BRACKET = Rs2TokenType("TRIGGER_BRACKET") // [ and ] in trigger headers
    @JvmField val TRIGGER_TYPE = Rs2TokenType("TRIGGER_TYPE")       // opnpc1, proc, label, etc.
    @JvmField val TRIGGER_SUBJECT = Rs2TokenType("TRIGGER_SUBJECT") // entity name (snape_grass)
    @JvmField val TRIGGER_SCRIPT_NAME = Rs2TokenType("TRIGGER_SCRIPT_NAME") // proc/label name

    // Operators & punctuation
    @JvmField val OPERATOR = Rs2TokenType("OPERATOR")
    @JvmField val PAREN = Rs2TokenType("PAREN")
    @JvmField val BRACE = Rs2TokenType("BRACE")
    @JvmField val COMMA = Rs2TokenType("COMMA")
    @JvmField val SEMICOLON = Rs2TokenType("SEMICOLON")
    @JvmField val DOT = Rs2TokenType("DOT")

    // Comments
    @JvmField val COMMENT = Rs2TokenType("COMMENT")

    // Whitespace & other
    @JvmField val WHITESPACE = Rs2TokenType("WHITESPACE")
    @JvmField val NEWLINE = Rs2TokenType("NEWLINE")
    @JvmField val CODE = Rs2TokenType("CODE")

    @JvmField val FILE = IFileElementType(Rs2Language)

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
    @JvmField val WHITESPACES = TokenSet.create(WHITESPACE, NEWLINE)
}
