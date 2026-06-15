package rs2.lsp

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class Rs2ConfigTokenType(debugName: String) : IElementType(debugName, Rs2ConfigLanguage)
class Rs2ConfigElementType(debugName: String) : IElementType(debugName, Rs2ConfigLanguage)

object Rs2ConfigTokenTypes {
    @JvmField val COMMENT = Rs2ConfigTokenType("CFG_COMMENT")

    // `[section]` headers (entity/definition name + its brackets)
    @JvmField val SECTION_BRACKET = Rs2ConfigTokenType("CFG_SECTION_BRACKET")
    @JvmField val SECTION_NAME = Rs2ConfigTokenType("CFG_SECTION_NAME")

    // left-hand side of `key=value` (property name, e.g. `cost`, `2dmodel`)
    @JvmField val KEY = Rs2ConfigTokenType("CFG_KEY")
    @JvmField val EQUALS = Rs2ConfigTokenType("CFG_EQUALS")

    // value tokens
    @JvmField val NUMBER = Rs2ConfigTokenType("CFG_NUMBER")
    @JvmField val COORD = Rs2ConfigTokenType("CFG_COORD")        // 0_50_50_10_10
    @JvmField val STRING = Rs2ConfigTokenType("CFG_STRING")
    @JvmField val BOOL = Rs2ConfigTokenType("CFG_BOOL")          // yes/no/true/false
    @JvmField val CONSTANT = Rs2ConfigTokenType("CFG_CONSTANT")  // ^name (also LHS of .constant)
    @JvmField val GAME_VAR = Rs2ConfigTokenType("CFG_GAME_VAR")  // %name
    @JvmField val IDENTIFIER = Rs2ConfigTokenType("CFG_IDENTIFIER") // entity refs / names
    @JvmField val PACK_NAME = Rs2ConfigTokenType("CFG_PACK_NAME")   // RHS of `id=name` in pack files (a declaration)
    @JvmField val COMMA = Rs2ConfigTokenType("CFG_COMMA")
    @JvmField val OPERATOR = Rs2ConfigTokenType("CFG_OPERATOR")  // : + - in values

    @JvmField val WHITESPACE = Rs2ConfigTokenType("CFG_WHITESPACE")
    @JvmField val NEWLINE = Rs2ConfigTokenType("CFG_NEWLINE")
    @JvmField val BAD = Rs2ConfigTokenType("CFG_BAD")

    // Composite element wrapping a SECTION_NAME so it can be a PsiNameIdentifierOwner
    // (a recognized declaration) for Find Usages / Go-to-usages navigation.
    @JvmField val SECTION = Rs2ConfigElementType("SECTION")

    @JvmField val FILE = IFileElementType(Rs2ConfigLanguage)

    @JvmField val COMMENTS = TokenSet.create(COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)
    @JvmField val WHITESPACES = TokenSet.create(WHITESPACE, NEWLINE)
}
