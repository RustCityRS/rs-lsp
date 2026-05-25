package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import java.util.concurrent.ConcurrentHashMap

object Rs2CommandRegistry {
    private val projectCommands = ConcurrentHashMap<String, Set<String>>()
    private val projectEntities = ConcurrentHashMap<String, Map<String, String>>()
    private val projectCommandParams = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val projectScriptParams = ConcurrentHashMap<String, Map<String, List<String>>>()

    fun getCommands(project: Project?): Set<String> {
        if (project == null) return emptySet()
        val key = project.locationHash
        return projectCommands.getOrPut(key) { loadPackNames(project, "command.pack") }
    }

    /** Returns map of entity name → pack type (e.g. "bronze_axe" → "obj") */
    fun getEntities(project: Project?): Map<String, String> {
        if (project == null) return emptyMap()
        val key = project.locationHash
        return projectEntities.getOrPut(key) { loadAllEntities(project) }
    }

    /** Returns map of command name → list of parameter types (e.g. ["inv", "obj", "int"]) */
    fun getCommandParams(project: Project?): Map<String, List<String>> {
        if (project == null) return emptyMap()
        val key = project.locationHash
        return projectCommandParams.getOrPut(key) { loadCommandParams(project) }
    }

    /** Returns map of script name → list of parameter types (e.g. ["int", "obj"]) */
    fun getScriptParams(project: Project?): Map<String, List<String>> {
        if (project == null) return emptyMap()
        val key = project.locationHash
        return projectScriptParams.getOrPut(key) { loadScriptParams(project) }
    }

    fun invalidate(project: Project) {
        projectCommands.remove(project.locationHash)
        projectEntities.remove(project.locationHash)
        projectCommandParams.remove(project.locationHash)
        projectScriptParams.remove(project.locationHash)
    }

    private val ENTITY_PACKS = listOf(
        "obj.pack", "npc.pack", "loc.pack", "inv.pack", "seq.pack",
        "spotanim.pack", "enum.pack", "struct.pack", "category.pack",
        "synth.pack", "hunt.pack", "idk.pack", "interface.pack",
        "overlayinterface.pack", "mesanim.pack", "fontmetrics.pack",
        "model.pack", "dbrow.pack", "dbtable.pack", "stat.pack",
        "midi.pack", "jingle.pack", "param.pack", "dbcolumn.pack",
        "npc_stat.pack", "npc_mode.pack", "locshape.pack", "varp.pack",
        "varn.pack", "vars.pack", "varbit.pack"
    )

    private fun loadScriptParams(project: Project): Map<String, List<String>> {
        val baseDir = project.guessProjectDir() ?: return emptyMap()
        val scriptsDir = baseDir.findFileByRelativePath("content/scripts") ?: return emptyMap()
        val params = mutableMapOf<String, List<String>>()
        collectScriptParams(scriptsDir, params)
        return params
    }

    private fun collectScriptParams(dir: com.intellij.openapi.vfs.VirtualFile, params: MutableMap<String, List<String>>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                collectScriptParams(child, params)
            } else if (child.extension == "rs2") {
                try {
                    child.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val trimmed = line.trim()
                            // Match [proc,name](type $var, ...) or [label,name](type $var, ...)
                            if (!trimmed.startsWith("[")) continue
                            val bracketEnd = trimmed.indexOf(']')
                            if (bracketEnd < 0) continue
                            val header = trimmed.substring(1, bracketEnd)
                            val comma = header.indexOf(',')
                            if (comma < 0) continue
                            val trigger = header.substring(0, comma).trim()
                            val name = header.substring(comma + 1).trim()
                            if (trigger != "proc" && trigger != "label" && trigger != "queue"
                                && trigger != "timer" && trigger != "softtimer") continue

                            val afterBracket = trimmed.substring(bracketEnd + 1)
                            val parenStart = afterBracket.indexOf('(')
                            if (parenStart < 0) {
                                params.putIfAbsent(name, emptyList())
                                continue
                            }
                            val parenEnd = afterBracket.indexOf(')', parenStart)
                            if (parenEnd < 0) {
                                params.putIfAbsent(name, emptyList())
                                continue
                            }
                            val paramStr = afterBracket.substring(parenStart + 1, parenEnd)
                            if (paramStr.isBlank()) {
                                params.putIfAbsent(name, emptyList())
                                continue
                            }
                            val types = paramStr.split(',').map { param ->
                                param.trim().split("\\s+".toRegex()).firstOrNull() ?: "int"
                            }
                            params.putIfAbsent(name, types)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadCommandParams(project: Project): Map<String, List<String>> {
        val baseDir = project.guessProjectDir() ?: return emptyMap()
        val engineFile = baseDir.findFileByRelativePath("content/scripts/engine.rs2") ?: return emptyMap()
        val params = mutableMapOf<String, List<String>>()
        try {
            engineFile.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("[command,")) continue
                    val bracketEnd = trimmed.indexOf(']')
                    if (bracketEnd < 0) continue
                    val name = trimmed.substring("[command,".length, bracketEnd).trim()

                    // Extract parameter types from (type $name, type $name, ...)
                    val afterBracket = trimmed.substring(bracketEnd + 1)
                    val parenStart = afterBracket.indexOf('(')
                    if (parenStart < 0) {
                        params[name] = emptyList()
                        continue
                    }
                    val parenEnd = afterBracket.indexOf(')', parenStart)
                    if (parenEnd < 0) {
                        params[name] = emptyList()
                        continue
                    }
                    val paramStr = afterBracket.substring(parenStart + 1, parenEnd)
                    if (paramStr.isBlank()) {
                        params[name] = emptyList()
                        continue
                    }
                    val types = paramStr.split(',').map { param ->
                        param.trim().split("\\s+".toRegex()).firstOrNull() ?: "int"
                    }
                    params[name] = types
                }
            }
        } catch (_: Exception) {}
        return params
    }

    private fun loadAllEntities(project: Project): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        for (pack in ENTITY_PACKS) {
            val typeName = pack.removeSuffix(".pack")
            for (name in loadPackNames(project, pack)) {
                entities.putIfAbsent(name, typeName)
            }
        }
        return entities
    }

    private fun loadPackNames(project: Project, fileName: String): Set<String> {
        val baseDir = project.guessProjectDir() ?: return emptySet()
        val packFile = baseDir.findFileByRelativePath("content/pack/$fileName") ?: return emptySet()
        val names = mutableSetOf<String>()
        try {
            packFile.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx >= 0) {
                        names.add(trimmed.substring(eqIdx + 1).trim())
                    }
                }
            }
        } catch (_: Exception) {}
        return names
    }
}
