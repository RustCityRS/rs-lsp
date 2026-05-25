package rs2.lsp

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import javax.swing.Icon

class Rs2CompletionContributor : CompletionContributor() {
    init {
        // Complete after ~ (proc calls)
        extend(CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(Rs2Language),
            Rs2CompletionProvider()
        )
    }

    override fun beforeCompletion(context: CompletionInitializationContext) {
        if (context.file.fileType == Rs2FileType.INSTANCE) {
            // Don't replace existing text — just insert
            context.replacementOffset = context.startOffset
        }
    }
}

class Rs2CompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val project = parameters.position.project
        val editor = parameters.editor
        val offset = parameters.offset
        val document = editor.document
        val text = document.text

        // Find what's before the cursor to determine context
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val linePrefix = text.substring(lineStart, offset)
        val trimmedPrefix = linePrefix.trimStart()

        // Determine the prefix being typed
        val typingPrefix = extractTypingPrefix(text, offset)

        // Check if we're inside a function/proc argument list first
        val argContext = detectArgumentContext(text, offset)

        when {
            // After ~ (not inside parens) → suggest procs
            argContext == null && linePrefix.lastIndexOf('~') > linePrefix.lastIndexOf(' ').coerceAtLeast(linePrefix.lastIndexOf('(')) -> {
                val prefix = text.substring(linePrefix.lastIndexOf('~') + lineStart + 1, offset)
                addScriptCompletions(project, "proc", prefix, result)
            }
            // After @ (not inside parens) → suggest labels
            argContext == null && linePrefix.lastIndexOf('@') > linePrefix.lastIndexOf(' ').coerceAtLeast(linePrefix.lastIndexOf('(')) -> {
                val prefix = text.substring(linePrefix.lastIndexOf('@') + lineStart + 1, offset)
                addScriptCompletions(project, "label", prefix, result)
            }
            // After ^ → suggest constants (works anywhere)
            linePrefix.lastIndexOf('^') > linePrefix.lastIndexOf(' ').coerceAtLeast(linePrefix.lastIndexOf('(')) -> {
                val prefix = text.substring(linePrefix.lastIndexOf('^') + lineStart + 1, offset)
                addConstantCompletions(project, prefix, result)
            }
            // After % → suggest game vars (works anywhere)
            linePrefix.lastIndexOf('%') > linePrefix.lastIndexOf(' ').coerceAtLeast(linePrefix.lastIndexOf('(')) -> {
                val prefix = text.substring(linePrefix.lastIndexOf('%') + lineStart + 1, offset)
                addGameVarCompletions(project, prefix, result)
            }
            // After [ at line start → suggest triggers
            trimmedPrefix.startsWith("[") && !trimmedPrefix.contains("]") -> {
                if (trimmedPrefix.contains(",")) {
                    // After [trigger, → suggest entities filtered by trigger type
                    val triggerType = trimmedPrefix.substringAfter("[").substringBefore(",").trim()
                    addTriggerSubjectCompletions(project, triggerType, typingPrefix, result)
                } else {
                    // After [ → suggest trigger types
                    addTriggerCompletions(typingPrefix, result)
                }
            }
            // After def_ → suggest type suffixes
            trimmedPrefix.contains("def_") && !trimmedPrefix.contains(" ") -> {
                addDefTypeCompletions(typingPrefix, result)
            }
            // Default: context-aware suggestions
            else -> {
                if (argContext != null) {
                    addContextualCompletions(project, typingPrefix, argContext, result)
                } else {
                    addCommandCompletions(project, typingPrefix, result)
                    addEntityCompletions(project, typingPrefix, result)
                    addKeywordCompletions(typingPrefix, result)
                }
            }
        }
    }

    private fun extractTypingPrefix(text: String, offset: Int): String {
        var start = offset - 1
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '_')) start--
        return text.substring(start + 1, offset)
    }

    private fun addCommandCompletions(project: Project, prefix: String, result: CompletionResultSet) {
        val commands = Rs2CommandRegistry.getCommands(project)
        val prefixedResult = result.withPrefixMatcher(prefix)
        for (cmd in commands) {
            prefixedResult.addElement(
                LookupElementBuilder.create(cmd)
                    .withTypeText("command")
                    .withBoldness(true)
                    .withInsertHandler { ctx, _ ->
                        ctx.document.insertString(ctx.tailOffset, "(")
                        ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                    }
            )
        }
    }

    private fun addEntityCompletions(project: Project, prefix: String, result: CompletionResultSet) {
        val entities = Rs2CommandRegistry.getEntities(project)
        val prefixedResult = result.withPrefixMatcher(prefix)
        for ((name, packType) in entities) {
            prefixedResult.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText(packType)
            )
        }
    }

    private fun addScriptCompletions(project: Project, trigger: String, prefix: String, result: CompletionResultSet) {
        val baseDir = project.guessProjectDir() ?: return
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return
        val prefixedResult = result.withPrefixMatcher(prefix)
        collectScriptNames(scriptsDir, trigger, prefixedResult)
    }

    private fun collectScriptNames(dir: com.intellij.openapi.vfs.VirtualFile, trigger: String, result: CompletionResultSet) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectScriptNames(child, trigger, result)
            } else if (child.extension == "rs2") {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("[$trigger,")) {
                                val end = trimmed.indexOf(']')
                                if (end > 0) {
                                    val name = trimmed.substring("[$trigger,".length, end)
                                    val cleanName = name.split('(').first().trim()
                                    result.addElement(
                                        LookupElementBuilder.create(cleanName)
                                            .withTypeText(trigger)
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun addConstantCompletions(project: Project, prefix: String, result: CompletionResultSet) {
        val baseDir = project.guessProjectDir() ?: return
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return
        val prefixedResult = result.withPrefixMatcher(prefix)
        collectConstants(scriptsDir, prefixedResult)
    }

    private fun collectConstants(dir: com.intellij.openapi.vfs.VirtualFile, result: CompletionResultSet) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectConstants(child, result)
            } else if (child.extension == "constant") {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@useLines
                            val name = trimmed.split('=').firstOrNull()?.trim()?.trimStart('^') ?: return@useLines
                            if (name.isNotEmpty()) {
                                result.addElement(
                                    LookupElementBuilder.create(name)
                                        .withTypeText("constant")
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun addGameVarCompletions(project: Project, prefix: String, result: CompletionResultSet) {
        val baseDir = project.guessProjectDir() ?: return
        val packDir = baseDir.findFileByRelativePath("content/pack") ?: return
        val prefixedResult = result.withPrefixMatcher(prefix)
        val varPacks = listOf("varp.pack", "varn.pack", "vars.pack", "varbit.pack")
        for (packName in varPacks) {
            val file = packDir.findChild(packName) ?: continue
            try {
                file.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val eqIdx = line.indexOf('=')
                        if (eqIdx >= 0) {
                            val name = line.substring(eqIdx + 1).trim()
                            prefixedResult.addElement(
                                LookupElementBuilder.create(name)
                                    .withTypeText(packName.removeSuffix(".pack"))
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun addTriggerCompletions(prefix: String, result: CompletionResultSet) {
        val triggers = listOf(
            "proc", "label", "debugproc", "clientscript",
            "opnpc1", "opnpc2", "opnpc3", "opnpc4", "opnpc5", "opnpcu", "opnpct",
            "aploc1", "aploc2", "aploc3", "aploc4", "aploc5", "aplocu", "aploct",
            "oploc1", "oploc2", "oploc3", "oploc4", "oploc5", "oplocu", "oploct",
            "opheld1", "opheld2", "opheld3", "opheld4", "opheld5", "opheldu", "opheldt",
            "opobj1", "opobj2", "opobj3", "opobj4", "opobj5", "opobju", "opobjt",
            "apnpc1", "apnpc2", "apnpc3", "apnpc4", "apnpc5", "apnpcu", "apnpct",
            "if_button", "if_button1", "if_button2", "if_button3", "if_button4", "if_button5",
            "if_close", "if_buttond", "inv_button1", "inv_button2", "inv_button3",
            "inv_button4", "inv_button5", "inv_buttond",
            "ai_timer", "ai_queue1", "ai_queue2", "ai_queue3", "ai_queue4", "ai_queue5",
            "queue", "timer", "softtimer", "walktrigger",
            "login", "logout", "tutorial", "advancestat", "changestat",
            "zone", "zoneexit", "mapzone", "mapzoneexit",
            "ai_spawn", "ai_despawn",
            "opplayer1", "opplayer2", "opplayer3", "opplayer4", "opplayer5",
            "applayer1", "applayer2", "applayer3", "applayer4", "applayer5",
        )
        val prefixedResult = result.withPrefixMatcher(prefix)
        for (trigger in triggers) {
            prefixedResult.addElement(
                LookupElementBuilder.create(trigger)
                    .withTypeText("trigger")
                    .withInsertHandler { ctx, _ ->
                        ctx.document.insertString(ctx.tailOffset, ",")
                        ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                    }
            )
        }
    }

    private fun addTriggerSubjectCompletions(project: Project, triggerType: String, prefix: String, result: CompletionResultSet) {
        val entities = Rs2CommandRegistry.getEntities(project)
        val prefixedResult = result.withPrefixMatcher(prefix)

        // Map trigger type to entity pack type
        val packType = when {
            triggerType.contains("npc") -> "npc"
            triggerType.contains("loc") -> "loc"
            triggerType.contains("obj") || triggerType.contains("held") -> "obj"
            triggerType.startsWith("if_") || triggerType.startsWith("inv_button") -> "interface"
            triggerType == "proc" || triggerType == "label" || triggerType == "queue"
                || triggerType == "timer" || triggerType == "softtimer"
                || triggerType == "walktrigger" || triggerType == "clientscript" -> {
                // For script triggers, suggest script names — no entity filter
                addScriptCompletions(project, triggerType, prefix, result)
                return
            }
            triggerType == "changestat" || triggerType == "advancestat" -> "stat"
            else -> null
        }

        if (packType != null) {
            for ((name, type) in entities) {
                if (type == packType) {
                    prefixedResult.addElement(
                        LookupElementBuilder.create(name).withTypeText(type)
                    )
                }
            }
        } else {
            // Unknown trigger type — show all entities
            for ((name, type) in entities) {
                prefixedResult.addElement(
                    LookupElementBuilder.create(name).withTypeText(type)
                )
            }
        }
    }

    private fun addDefTypeCompletions(prefix: String, result: CompletionResultSet) {
        val types = listOf(
            "def_int", "def_string", "def_boolean", "def_coord", "def_obj", "def_npc",
            "def_loc", "def_namedobj", "def_component", "def_interface", "def_inv",
            "def_enum", "def_stat", "def_seq", "def_synth", "def_category",
            "def_struct", "def_dbrow", "def_dbtable", "def_param", "def_hunt",
            "def_char", "def_spotanim", "def_long"
        )
        val prefixedResult = result.withPrefixMatcher(prefix)
        for (type in types) {
            prefixedResult.addElement(
                LookupElementBuilder.create(type)
                    .withTypeText("type")
                    .withInsertHandler { ctx, _ ->
                        ctx.document.insertString(ctx.tailOffset, " \$")
                        ctx.editor.caretModel.moveToOffset(ctx.tailOffset)
                    }
            )
        }
    }

    private fun addKeywordCompletions(prefix: String, result: CompletionResultSet) {
        val keywords = listOf(
            "if", "else", "while", "return", "switch_int", "switch_obj",
            "switch_loc", "switch_npc", "switch_string", "switch_coord",
            "case", "default", "calc", "true", "false", "null"
        )
        val prefixedResult = result.withPrefixMatcher(prefix)
        for (kw in keywords) {
            prefixedResult.addElement(
                LookupElementBuilder.create(kw)
                    .withTypeText("keyword")
                    .withBoldness(true)
            )
        }
    }

    private data class ArgContext(val commandName: String, val argIndex: Int, val isScript: Boolean = false)

    private fun detectArgumentContext(text: String, offset: Int): ArgContext? {
        // The completion framework inserts a dummy identifier at cursor.
        // Walk backwards from the start of what we're typing (skip the dummy/prefix).
        var startPos = offset - 1
        // Skip back past any identifier chars (the prefix being typed or dummy)
        while (startPos >= 0 && (text[startPos].isLetterOrDigit() || text[startPos] == '_')) startPos--

        // Now walk backwards to find the enclosing command(
        var depth = 0
        var argIndex = 0
        var i = startPos
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        // Found the opening paren — extract the name before it
                        var nameStart = i - 1
                        while (nameStart >= 0 && (text[nameStart] == ' ' || text[nameStart] == '\t')) nameStart--
                        val nameEnd = nameStart + 1
                        while (nameStart >= 0 && (text[nameStart].isLetterOrDigit() || text[nameStart] == '_')) nameStart--
                        nameStart++
                        if (nameStart < nameEnd) {
                            val name = text.substring(nameStart, nameEnd)
                            // Check if preceded by ~ or @ (script call)
                            val prefixIdx = nameStart - 1
                            val isScript = prefixIdx >= 0 && (text[prefixIdx] == '~' || text[prefixIdx] == '@')
                            return ArgContext(name, argIndex, isScript)
                        }
                        return null
                    }
                    depth--
                }
                ',' -> {
                    if (depth == 0) argIndex++
                }
            }
            i--
        }
        return null
    }

    private fun addContextualCompletions(
        project: Project, prefix: String, ctx: ArgContext, result: CompletionResultSet
    ) {
        val paramTypes = if (ctx.isScript) {
            val scriptParams = Rs2CommandRegistry.getScriptParams(project)
            scriptParams[ctx.commandName]
        } else {
            val commandParams = Rs2CommandRegistry.getCommandParams(project)
            commandParams[ctx.commandName]
        }

        if (paramTypes == null || ctx.argIndex >= paramTypes.size) {
            // Unknown command/script or arg position — show everything
            addCommandCompletions(project, prefix, result)
            addEntityCompletions(project, prefix, result)
            addKeywordCompletions(prefix, result)
            return
        }

        val expectedType = paramTypes[ctx.argIndex]
        val entities = Rs2CommandRegistry.getEntities(project)
        val prefixedResult = result.withPrefixMatcher(prefix)

        // Map type to which pack entities to suggest
        val typeToPackMapping = mapOf(
            "obj" to "obj", "namedobj" to "obj",
            "npc" to "npc", "loc" to "loc", "inv" to "inv",
            "stat" to "stat", "seq" to "seq", "synth" to "synth",
            "enum" to "enum", "category" to "category",
            "struct" to "struct", "param" to "param",
            "interface" to "interface", "component" to "interface",
            "hunt" to "hunt", "spotanim" to "spotanim",
            "idk" to "idk", "model" to "model",
            "dbrow" to "dbrow", "dbtable" to "dbtable",
            "midi" to "midi", "jingle" to "jingle",
            "npc_stat" to "npc_stat", "npc_mode" to "npc_mode",
            "varp" to "varp", "varn" to "varn", "vars" to "vars",
            "overlayinterface" to "overlayinterface",
        )

        val packFilter = typeToPackMapping[expectedType]

        when {
            expectedType == "int" -> {
                // For int args, suggest constants and numbers
                addConstantCompletions(project, prefix, result)
                addCommandCompletions(project, prefix, result)
            }
            expectedType == "string" -> {
                // For string args, not much to suggest
                addCommandCompletions(project, prefix, result)
            }
            expectedType == "boolean" -> {
                prefixedResult.addElement(LookupElementBuilder.create("true").withTypeText("boolean"))
                prefixedResult.addElement(LookupElementBuilder.create("false").withTypeText("boolean"))
            }
            expectedType == "coord" -> {
                addCommandCompletions(project, prefix, result)
            }
            packFilter != null -> {
                // Only suggest entities of the matching pack type
                for ((name, packType) in entities) {
                    if (packType == packFilter) {
                        prefixedResult.addElement(
                            LookupElementBuilder.create(name).withTypeText(packType)
                        )
                    }
                }
            }
            else -> {
                // Unknown type — show everything
                addCommandCompletions(project, prefix, result)
                addEntityCompletions(project, prefix, result)
            }
        }
    }
}
