package rs2.lsp

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

class Rs2Lexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var state = 0

    companion object {
        private val KEYWORDS = setOf(
            "if", "else", "while", "return", "switch", "case", "default", "calc", "null",
            "true", "false",
            "switch_int", "switch_obj", "switch_loc", "switch_npc", "switch_string",
            "switch_coord", "switch_component", "switch_namedobj", "switch_stat",
            "switch_seq", "switch_inv", "switch_enum", "switch_category"
        )
        // Always-orange type keywords (def_ forms + pure primitives)
        private val DEF_TYPE_KEYWORDS = setOf(
            "def_int", "def_string", "def_boolean", "def_coord", "def_obj", "def_npc",
            "def_loc", "def_namedobj", "def_component", "def_interface", "def_inv",
            "def_enum", "def_stat", "def_seq", "def_synth", "def_category",
            "def_struct", "def_dbrow", "def_dbtable", "def_param", "def_hunt",
            "def_char", "def_spotanim", "def_long", "def_npc_stat", "def_fontmetrics",
            "def_model", "def_idk", "def_midi", "def_jingle", "def_npc_mode",
            "def_locshape", "def_overlayinterface", "def_dbcolumn", "def_controller",
            "def_player_uid", "def_softtimer", "def_timer", "def_queue",
            "def_walktrigger", "def_npc_uid",
            "int", "string", "boolean", "long", "char",
            "proc", "label"
        )
        // Overloaded type names — only highlighted as types when in a type position (followed by $)
        private val BARE_TYPE_KEYWORDS = setOf(
            "obj", "npc", "loc", "namedobj", "component", "interface", "inv", "coord",
            "enum", "stat", "seq", "synth", "category", "struct", "dbrow",
            "dbtable", "param", "hunt", "spotanim", "npc_stat", "fontmetrics",
            "model", "idk", "midi", "jingle", "npc_mode", "locshape",
            "overlayinterface", "dbcolumn", "controller",
            "player_uid", "softtimer", "timer", "queue", "walktrigger", "npc_uid"
        )
        private val SCRIPT_NAME_TRIGGERS = setOf(
            "proc", "label", "debugproc", "clientscript", "queue",
            "timer", "softtimer", "walktrigger"
        )
        private val SCRIPT_REF_COMMANDS = setOf(
            "queue", "timer", "softtimer", "walktrigger",
            "settimer", "cleartimer", "clearsofttimer",
            "gosub", "jump"
        )
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
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            tokenType = null
            return
        }

        // Inside a string: scan until " (end), < (interpolation start), or newline
        if (state == 1) {
            val c = buffer[tokenStart]
            if (c == '<') {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.OPERATOR
                state = 2
            } else if (c == '"') {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.STRING
                state = 0
            } else {
                tokenEnd = tokenStart
                while (tokenEnd < endOffset && buffer[tokenEnd] != '"' && buffer[tokenEnd] != '<' && buffer[tokenEnd] != '\n') tokenEnd++
                if (tokenEnd < endOffset && buffer[tokenEnd] == '"') {
                    tokenEnd++
                    state = 0
                }
                tokenType = Rs2TokenTypes.STRING
            }
            return
        }

        // Inside string interpolation: tokenize normally until >
        if (state == 2) {
            val c = buffer[tokenStart]
            if (c == '>') {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.OPERATOR
                state = 1
                return
            }
            // Tokenize identifiers, $vars, parens, commas normally — fall through to main logic
        }

        val c = buffer[tokenStart]

        when {
            // Whitespace
            c == ' ' || c == '\t' || c == '\r' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && (buffer[tokenEnd] == ' ' || buffer[tokenEnd] == '\t' || buffer[tokenEnd] == '\r')) tokenEnd++
                tokenType = Rs2TokenTypes.WHITESPACE
            }
            c == '\n' -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.NEWLINE
            }

            // Comments
            c == '/' && peek(1) == '/' -> {
                tokenEnd = tokenStart
                while (tokenEnd < endOffset && buffer[tokenEnd] != '\n') tokenEnd++
                tokenType = Rs2TokenTypes.COMMENT
            }
            c == '/' && peek(1) == '*' -> {
                tokenEnd = tokenStart + 2
                while (tokenEnd + 1 < endOffset && !(buffer[tokenEnd] == '*' && buffer[tokenEnd + 1] == '/')) tokenEnd++
                if (tokenEnd + 1 < endOffset) tokenEnd += 2 else tokenEnd = endOffset
                tokenType = Rs2TokenTypes.COMMENT
            }

            // String literal — enter string state
            c == '"' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && buffer[tokenEnd] != '"' && buffer[tokenEnd] != '<' && buffer[tokenEnd] != '\n') tokenEnd++
                if (tokenEnd < endOffset && buffer[tokenEnd] == '"') {
                    tokenEnd++
                    // Simple string with no interpolation
                } else {
                    // Stopped at < or newline — enter string state
                    state = 1
                }
                tokenType = Rs2TokenTypes.STRING
            }

            // Trigger header: [trigger,subject]
            c == '[' && isAtLineStart() -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.TRIGGER_BRACKET
            }

            // Local var: $name
            c == '$' && peekIsIdentChar(1) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2TokenTypes.LOCAL_VAR
            }

            // Game var: %name
            c == '%' && peekIsIdentChar(1) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2TokenTypes.GAME_VAR
            }

            // Constant: ^name
            c == '^' && peekIsIdentChar(1) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2TokenTypes.CONSTANT
            }

            // Proc call: ~name
            c == '~' && peekIsIdentChar(1) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2TokenTypes.PROC_CALL
            }

            // Jump call: @name
            c == '@' && peekIsIdentChar(1) -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                tokenType = Rs2TokenTypes.JUMP_CALL
            }

            // Numbers (including coord literals like 0_50_50_10_10)
            c.isDigit() -> {
                tokenEnd = tokenStart + 1
                var hasUnderscore = false
                while (tokenEnd < endOffset && (buffer[tokenEnd].isDigit() || buffer[tokenEnd] == '_')) {
                    if (buffer[tokenEnd] == '_') hasUnderscore = true
                    tokenEnd++
                }
                tokenType = if (hasUnderscore) Rs2TokenTypes.COORD_LITERAL else Rs2TokenTypes.NUMBER
            }

            // Identifier or keyword
            c.isLetter() || c == '_' -> {
                tokenEnd = tokenStart + 1
                while (tokenEnd < endOffset && isIdentChar(buffer[tokenEnd])) tokenEnd++
                val word = buffer.subSequence(tokenStart, tokenEnd).toString()
                tokenType = when {
                    word == "true" -> Rs2TokenTypes.TRUE
                    word == "false" -> Rs2TokenTypes.FALSE
                    word == "null" -> Rs2TokenTypes.NULL
                    word in KEYWORDS -> Rs2TokenTypes.KEYWORD
                    word in DEF_TYPE_KEYWORDS -> Rs2TokenTypes.TYPE_NAME
                    word in BARE_TYPE_KEYWORDS -> {
                        when {
                            isInTypePosition() -> Rs2TokenTypes.TYPE_NAME
                            isInTriggerHeader() -> classifyTriggerWord(word)
                            else -> Rs2TokenTypes.IDENTIFIER
                        }
                    }
                    else -> {
                        if (isInTriggerHeader()) {
                            classifyTriggerWord(word)
                        } else if (isFollowedByParen()) {
                            Rs2TokenTypes.COMMAND
                        } else if (isFirstArgOfScriptCommand()) {
                            Rs2TokenTypes.PROC_CALL
                        } else {
                            Rs2TokenTypes.IDENTIFIER
                        }
                    }
                }
            }

            // Operators
            c == '=' || c == '!' || c == '<' || c == '>' || c == '&' || c == '|' || c == '+' || c == '-' || c == '*' || c == '/' -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.OPERATOR
            }

            // Punctuation
            c == '(' || c == ')' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.PAREN }
            c == '{' || c == '}' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.BRACE }
            c == ']' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.TRIGGER_BRACKET }
            c == ',' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.COMMA }
            c == ';' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.SEMICOLON }
            c == '.' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.DOT }
            c == ':' -> { tokenEnd = tokenStart + 1; tokenType = Rs2TokenTypes.OPERATOR }

            else -> {
                tokenEnd = tokenStart + 1
                tokenType = Rs2TokenTypes.CODE
            }
        }
    }

    private fun peek(offset: Int): Char {
        val pos = tokenStart + offset
        return if (pos < endOffset) buffer[pos] else ' '
    }

    private fun peekIsIdentChar(offset: Int): Boolean {
        val pos = tokenStart + offset
        return pos < endOffset && isIdentChar(buffer[pos])
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isInTypePosition(): Boolean {
        // Type position 1: followed by $variable (parameter declaration)
        var i = tokenEnd
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i++
        if (i < endOffset && buffer[i] == '$') return true

        // Type position 2: inside a return type list )(type, type)
        // Check if we're inside parens preceded by )
        if (i < endOffset && (buffer[i] == ',' || buffer[i] == ')')) {
            // Look backwards past the word for ( preceded by )
            var j = tokenStart - 1
            while (j >= startOffset && (buffer[j] == ' ' || buffer[j] == '\t')) j--
            if (j >= startOffset && (buffer[j] == '(' || buffer[j] == ',')) {
                // Keep scanning back to see if this ( is preceded by )
                var k = j
                if (buffer[k] == ',') {
                    // We're after a comma — find the opening (
                    while (k >= startOffset && buffer[k] != '(') k--
                }
                if (k > startOffset) {
                    var m = k - 1
                    while (m >= startOffset && (buffer[m] == ' ' || buffer[m] == '\t')) m--
                    if (m >= startOffset && buffer[m] == ')') return true
                }
            }
        }

        return false
    }

    private fun isFollowedByParen(): Boolean {
        var i = tokenEnd
        while (i < endOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i++
        return i < endOffset && buffer[i] == '('
    }

    private fun isAtLineStart(): Boolean {
        var i = tokenStart - 1
        while (i >= startOffset) {
            val ch = buffer[i]
            if (ch == '\n') return true
            if (ch != ' ' && ch != '\t' && ch != '\r') return false
            i--
        }
        return true // start of file
    }

    private fun isInTriggerHeader(): Boolean {
        // Look backwards for an unmatched '[' at line start
        var i = tokenStart - 1
        var depth = 0
        while (i >= startOffset) {
            val ch = buffer[i]
            if (ch == '\n') return false
            if (ch == ']') depth++
            if (ch == '[') {
                if (depth == 0) return true
                depth--
            }
            i--
        }
        return false
    }

    private fun classifyTriggerWord(word: String): IElementType {
        // If we haven't seen a comma yet in this trigger header, it's the trigger type
        var i = tokenStart - 1
        var seenComma = false
        while (i >= startOffset) {
            val ch = buffer[i]
            if (ch == '\n') break
            if (ch == '[') break
            if (ch == ',') { seenComma = true; break }
            i--
        }
        if (!seenComma) return Rs2TokenTypes.TRIGGER_TYPE

        // It's the subject — check if the trigger type is proc/label/etc
        val triggerType = extractTriggerType()
        return if (triggerType in SCRIPT_NAME_TRIGGERS) {
            Rs2TokenTypes.TRIGGER_SCRIPT_NAME
        } else {
            Rs2TokenTypes.TRIGGER_SUBJECT
        }
    }

    private fun extractTriggerType(): String {
        // Find the [ then read the trigger type (text between [ and ,)
        var i = tokenStart - 1
        while (i >= startOffset && buffer[i] != '[') i--
        if (i < startOffset) return ""
        i++ // skip [
        val start = i
        while (i < endOffset && buffer[i] != ',' && buffer[i] != ']') i++
        return buffer.subSequence(start, i).toString().trim()
    }

    private fun isFirstArgOfScriptCommand(): Boolean {
        var i = tokenStart - 1
        while (i >= startOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i--
        if (i < startOffset || buffer[i] != '(') return false
        i--
        while (i >= startOffset && (buffer[i] == ' ' || buffer[i] == '\t')) i--
        val end = i + 1
        while (i >= startOffset && isIdentChar(buffer[i])) i--
        val cmdName = buffer.subSequence(i + 1, end).toString()
        return cmdName in SCRIPT_REF_COMMANDS
    }
}
