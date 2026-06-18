package rs2.lsp

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Highlighting lexer for the RuneScript 2 config/pack family. Line-oriented:
 *
 *   state 0 (KEY)     start of a line, before the first `=`
 *   state 1 (VALUE)   after a `=`, until end of line
 *   state 2 (SECTION) after `[`, until the closing `]`
 *
 * A newline always resets to KEY context. This single grammar covers:
 *   - configs : `[section]` headers + `key=value` (.obj/.npc/.dbtable/.anim/…)
 *   - packs   : `id=name` (.pack), bare `id` (.order), `id=hash` (.hashes)
 *   - constants: `^name = value` (.constant)
 */
class Rs2ConfigLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var state = 0

    private companion object {
        const val KEY = 0
        const val VALUE = 1
        const val SECTION = 2
        const val PACK_VALUE = 3
        val BOOLS = setOf("yes", "no", "true", "false")
    }

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenEnd = startOffset
        this.state = initialState
        advance()
    }

    override fun getState(): Int = state
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        advanceToken()
        // Forward-progress guarantee. IntelliJ throws "Lexer is not advancing"
        // (and the file then fails to open) if a token is produced without moving
        // the offset. The logic below should never do that, but this backstops any
        // future edit: degrade a stuck position to a single BAD char rather than
        // freezing the editor.
        if (tokenType != null && tokenEnd <= tokenStart) {
            tokenEnd = tokenStart + 1
            tokenType = Rs2ConfigTokenTypes.BAD
        }
    }

    private fun advanceToken() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            return
        }

        val c = buffer[tokenStart]

        // Newline — always returns to key context for the next line.
        if (c == '\n') {
            tokenEnd = tokenStart + 1
            tokenType = Rs2ConfigTokenTypes.NEWLINE
            state = KEY
            return
        }
        // Horizontal whitespace.
        if (c == ' ' || c == '\t' || c == '\r') {
            tokenEnd = tokenStart + 1
            while (tokenEnd < endOffset && (buffer[tokenEnd] == ' ' || buffer[tokenEnd] == '\t' || buffer[tokenEnd] == '\r')) tokenEnd++
            tokenType = Rs2ConfigTokenTypes.WHITESPACE
            return
        }
        // Comments (any context).
        if (c == '/' && peek(1) == '/') {
            tokenEnd = tokenStart
            while (tokenEnd < endOffset && buffer[tokenEnd] != '\n') tokenEnd++
            tokenType = Rs2ConfigTokenTypes.COMMENT
            return
        }
        if (c == '/' && peek(1) == '*') {
            tokenEnd = tokenStart + 2
            while (tokenEnd + 1 < endOffset && !(buffer[tokenEnd] == '*' && buffer[tokenEnd + 1] == '/')) tokenEnd++
            tokenEnd = if (tokenEnd + 1 < endOffset) tokenEnd + 2 else endOffset
            tokenType = Rs2ConfigTokenTypes.COMMENT
            return
        }

        when (state) {
            SECTION -> {
                if (c == ']') {
                    tokenEnd = tokenStart + 1
                    tokenType = Rs2ConfigTokenTypes.SECTION_BRACKET
                    state = KEY
                } else {
                    tokenEnd = tokenStart
                    while (tokenEnd < endOffset && buffer[tokenEnd] != ']' && buffer[tokenEnd] != '\n') tokenEnd++
                    tokenType = Rs2ConfigTokenTypes.SECTION_NAME
                }
            }

            VALUE, PACK_VALUE -> advanceValue(c)

            else -> advanceKey(c)
        }
    }

    private fun advanceKey(c: Char) {
        when {
            c == '[' -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2ConfigTokenTypes.SECTION_BRACKET
                state = SECTION
            }
            c == '=' -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2ConfigTokenTypes.EQUALS
                // A numeric LHS marks a pack registration line (`24=name`); its
                // RHS name is a navigable entity declaration, not a plain value.
                state = if (lineKeyIsNumeric()) PACK_VALUE else VALUE
            }
            // `^name` defining line in .constant files
            c == '^' && isIdentChar(peek(1)) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2ConfigTokenTypes.CONSTANT
            }
            isIdentChar(c) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                // `key=` ⇒ a property name; otherwise a bare id/name (.order/.pack value).
                tokenType = when {
                    nextNonBlankIsEquals(tokenEnd) -> Rs2ConfigTokenTypes.KEY
                    isAllDigits(tokenStart, tokenEnd) -> Rs2ConfigTokenTypes.NUMBER
                    else -> Rs2ConfigTokenTypes.IDENTIFIER
                }
            }
            else -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2ConfigTokenTypes.BAD
            }
        }
    }

    private fun advanceValue(c: Char) {
        when {
            c == '=' -> { tokenEnd = tokenStart + 1; tokenType = Rs2ConfigTokenTypes.EQUALS }
            c == ',' -> { tokenEnd = tokenStart + 1; tokenType = Rs2ConfigTokenTypes.COMMA }
            c == '"' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && buffer[tokenEnd] != '"' && buffer[tokenEnd] != '\n') tokenEnd++
                if (tokenEnd < endOffset && buffer[tokenEnd] == '"') tokenEnd++
                tokenType = Rs2ConfigTokenTypes.STRING
            }
            c == '^' && isIdentChar(peek(1)) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2ConfigTokenTypes.CONSTANT
            }
            c == '%' && isIdentChar(peek(1)) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2ConfigTokenTypes.GAME_VAR
            }
            c.isDigit() || (c == '-' && peek(1).isDigit()) -> {
                tokenEnd = tokenStart + 1
                var hasUnderscore = false
                while (tokenEnd < endOffset && (buffer[tokenEnd].isDigit() || buffer[tokenEnd] == '_')) {
                    if (buffer[tokenEnd] == '_') hasUnderscore = true
                    tokenEnd++
                }
                tokenType = if (hasUnderscore) Rs2ConfigTokenTypes.COORD else Rs2ConfigTokenTypes.NUMBER
            }
            isIdentChar(c) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                val word = buffer.subSequence(tokenStart, tokenEnd).toString()
                tokenType = when {
                    state == PACK_VALUE -> Rs2ConfigTokenTypes.PACK_NAME
                    word in BOOLS -> Rs2ConfigTokenTypes.BOOL
                    else -> Rs2ConfigTokenTypes.IDENTIFIER
                }
            }
            c == ':' || c == '+' || c == '-' || c == '*' || c == '/' ||
                c == '<' || c == '>' || c == '!' || c == '&' || c == '|' -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2ConfigTokenTypes.OPERATOR
            }
            else -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2ConfigTokenTypes.BAD
            }
        }
    }

    private fun peek(offset: Int): Char {
        val pos = tokenStart + offset
        return if (pos < endOffset) buffer[pos] else ' '
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isAllDigits(from: Int, to: Int): Boolean {
        for (i in from until to) if (!buffer[i].isDigit()) return false
        return to > from
    }

    private fun nextNonBlankIsEquals(from: Int): Boolean {
        var i = from
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i++
        return i < endOffset && buffer[i] == '='
    }

    // True when the key to the left of the `=` at [tokenStart] is all digits —
    // i.e. a pack registration line (`24=name`) rather than a config `key=value`.
    private fun lineKeyIsNumeric(): Boolean {
        var i = tokenStart - 1
        while (i >= startOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i--
        val end = i + 1
        while (i >= startOffset && buffer[i] != '\n' && buffer[i] != ' ' && buffer[i] != '\t') i--
        val start = i + 1
        if (start >= end) return false
        for (j in start until end) if (!buffer[j].isDigit()) return false
        return true
    }
}
