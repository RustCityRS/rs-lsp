package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * PSI-free navigation logic for config/pack files, shared by the goto-declaration
 * handler ([Rs2GotoDeclarationHandler]) and the action wrapper
 * ([Rs2GotoDeclarationWrapper]). Everything here works off plain text, so it is
 * unaffected by the 2.5 MB PSI file-size limit and resolves correctly inside large
 * `all.*` dumps.
 *
 * Declaration lookups use [Rs2ConfigDeclarationIndex] as a fast path, then fall
 * back to a direct VFS scan so navigation keeps working even before the index is
 * built (or if it is unavailable) — the index is an optimization, never a
 * prerequisite.
 */
object Rs2ConfigNav {
    fun isEntityChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '+'

    /**
     * Resolve a *reference* at [offset] in [text] — an entity, `^constant`,
     * `%gamevar`, or `table:column` — to its declaration, or null.
     */
    fun resolveReference(project: Project, text: CharSequence, offset: Int): Rs2ConfigDeclTarget? {
        val len = text.length
        if (offset < 0 || offset > len) return null
        var start = offset
        while (start > 0 && isEntityChar(text[start - 1])) start--
        var end = offset
        while (end < len && isEntityChar(text[end])) end++
        if (start >= end) return null
        val word = text.subSequence(start, end).toString()

        return when (if (start > 0) text[start - 1] else ' ') {
            '^' -> findConstant(project, word)
            '%' -> findGameVar(project, word)
            else -> {
                val full = buildFullEntityName(text, start, word)
                if (full != word && full.contains(':')) {
                    val table = full.substringBefore(':')
                    val column = full.substringAfter(':')
                    if (word == column) findDbColumn(project, table, column)?.let { return it }
                }
                (if (full != word) findEntity(project, full) else null) ?: findEntity(project, word)
            }
        }
    }

    /**
     * If [offset] sits on a *declaration* — a `[name]` section header or the name
     * of a numeric `id=name` pack entry — return a target to it, else null.
     */
    fun declarationAtCaret(project: Project, file: VirtualFile, text: CharSequence, offset: Int): Rs2ConfigDeclTarget? {
        val len = text.length
        if (offset < 0 || offset > len) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < len && text[lineEnd] != '\n') lineEnd++

        var s = lineStart
        while (s < lineEnd && (text[s] == ' ' || text[s] == '\t' || text[s] == '\r')) s++
        if (s >= lineEnd) return null

        // `[name]` section header.
        if (text[s] == '[') {
            var b = s + 1
            while (b < lineEnd && text[b] != ']') b++
            val nameStart = s + 1
            if (b > nameStart && offset in nameStart..b) {
                val name = text.subSequence(nameStart, b).toString().trim()
                if (name.isNotEmpty()) return Rs2ConfigDeclTarget(project, file, nameStart, name)
            }
            return null
        }

        // `id=name` pack entry (numeric id).
        val eq = indexOfIn(text, lineStart, lineEnd, '=')
        if (eq >= 0 && allDigits(text, s, eq)) {
            var v = eq + 1
            while (v < lineEnd && (text[v] == ' ' || text[v] == '\t')) v++
            if (v < lineEnd && offset in v..lineEnd) {
                val name = text.subSequence(v, lineEnd).toString().trim()
                if (name.isNotEmpty()) return Rs2ConfigDeclTarget(project, file, v, name)
            }
        }
        return null
    }

    // === Declaration lookups: index fast path, then VFS fallback ===

    fun findEntity(project: Project, name: String): Rs2ConfigDeclTarget? =
        resolveDecl(project, name, Rs2DeclKind.SECTION, Rs2DeclKind.PACK)
            ?: ifIndexUnavailable(project) { findEntityVfs(project, name) }

    fun findGameVar(project: Project, name: String): Rs2ConfigDeclTarget? =
        resolveDecl(project, name, Rs2DeclKind.SECTION, Rs2DeclKind.PACK)
            ?: ifIndexUnavailable(project) { findGameVarVfs(project, name) }

    fun findConstant(project: Project, name: String): Rs2ConfigDeclTarget? =
        resolveDecl(project, name, Rs2DeclKind.CONSTANT)
            ?: ifIndexUnavailable(project) { findConstantVfs(project, name) }

    // The VFS fallback walks/reads files, so only pay for it while the index is
    // still building (dumb mode). In smart mode an index miss is authoritative —
    // the name simply isn't a content/ declaration — so we skip the scan.
    private inline fun ifIndexUnavailable(project: Project, body: () -> Rs2ConfigDeclTarget?): Rs2ConfigDeclTarget? =
        if (com.intellij.openapi.project.DumbService.isDumb(project)) body() else null

    private fun resolveDecl(project: Project, name: String, vararg kinds: Int): Rs2ConfigDeclTarget? {
        val hit = Rs2ConfigDeclarationIndex.find(project, name).firstOrNull { it.second.kind in kinds }
            ?: return null
        return Rs2ConfigDeclTarget(project, hit.first, hit.second.offset, name)
    }

    private fun configExtension(packType: String): String = when (packType) {
        "interface" -> "if"
        "namedobj" -> "obj"
        else -> packType
    }

    private fun findEntityVfs(project: Project, name: String): Rs2ConfigDeclTarget? {
        val baseDir = project.guessProjectDir() ?: return null
        val packDir = baseDir.findFileByRelativePath("content/pack")
        if (packDir != null) {
            for (packFile in packDir.children.filter { it.extension == "pack" }) {
                if (!packDeclares(packFile, name)) continue
                findConfigSection(project, baseDir, configExtension(packFile.nameWithoutExtension), name)?.let { return it }
                return findPackEntry(project, packFile, name)
            }
            // Not found via a matching pack type — scan every pack as a last resort.
            for (packFile in packDir.children.filter { it.extension == "pack" }) {
                findPackEntry(project, packFile, name)?.let { return it }
            }
        }
        return null
    }

    private fun findGameVarVfs(project: Project, name: String): Rs2ConfigDeclTarget? {
        val baseDir = project.guessProjectDir() ?: return null
        for (packType in listOf("varp", "varn", "vars", "varbit")) {
            val packFile = baseDir.findFileByRelativePath("content/pack/$packType.pack") ?: continue
            if (!packDeclares(packFile, name)) continue
            findConfigSection(project, baseDir, packType, name)?.let { return it }
            return findPackEntry(project, packFile, name)
        }
        return null
    }

    private fun findConstantVfs(project: Project, name: String): Rs2ConfigDeclTarget? {
        val contentDir = project.guessProjectDir()?.findFileByRelativePath("content") ?: return null
        return searchConstant(contentDir, project, name)
    }

    private fun packDeclares(packFile: VirtualFile, name: String): Boolean {
        val text = runCatching { VfsUtilCore.loadText(packFile) }.getOrNull() ?: return false
        return text.lineSequence().any { line ->
            val eq = line.indexOf('=')
            eq >= 0 && line.substring(eq + 1).trim() == name
        }
    }

    private fun findPackEntry(project: Project, packFile: VirtualFile, name: String): Rs2ConfigDeclTarget? {
        val text = runCatching { VfsUtilCore.loadText(packFile) }.getOrNull() ?: return null
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val eq = line.indexOf('=')
            if (eq < 0 || line.substring(eq + 1).trim() != name) continue
            val namePos = line.indexOf(name, eq)
            val offset = lines.take(index).sumOf { it.length + 1 } + (if (namePos >= 0) namePos else eq + 1)
            return Rs2ConfigDeclTarget(project, packFile, offset, name)
        }
        return null
    }

    /** Find a `[name]` section in any `*.ext` config file under content/ (skipping _unpack dumps). */
    private fun findConfigSection(project: Project, baseDir: VirtualFile, ext: String, name: String): Rs2ConfigDeclTarget? {
        val contentDir = baseDir.findFileByRelativePath("content") ?: return null
        return searchConfigSection(project, contentDir, ext, name)
    }

    private fun searchConfigSection(project: Project, dir: VirtualFile, ext: String, name: String): Rs2ConfigDeclTarget? {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name == "_unpack") continue
                searchConfigSection(project, child, ext, name)?.let { return it }
            } else if (child.extension == ext) {
                val text = runCatching { VfsUtilCore.loadText(child) }.getOrNull() ?: continue
                val target = "[$name]"
                val lines = text.lines()
                for ((index, line) in lines.withIndex()) {
                    if (line.trimStart() == target) {
                        val offset = lines.take(index).sumOf { it.length + 1 } + line.indexOf('[') + 1
                        return Rs2ConfigDeclTarget(project, child, offset, name)
                    }
                }
            }
        }
        return null
    }

    private fun searchConstant(dir: VirtualFile, project: Project, name: String): Rs2ConfigDeclTarget? {
        for (child in dir.children) {
            if (child.isDirectory) {
                searchConstant(child, project, name)?.let { return it }
            } else if (child.extension == "constant") {
                val text = runCatching { VfsUtilCore.loadText(child) }.getOrNull() ?: continue
                val lines = text.lines()
                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq < 0) continue
                    val key = trimmed.substring(0, eq).trim().trimStart('^')
                    if (key == name) {
                        val caret = line.indexOf('^')
                        val col = if (caret >= 0) caret else line.indexOf(name)
                        val offset = lines.take(index).sumOf { it.length + 1 } + (if (col >= 0) col else 0)
                        return Rs2ConfigDeclTarget(project, child, offset, name)
                    }
                }
            }
        }
        return null
    }

    /**
     * Navigate a dbtable `table:column` reference to its `column=<name>,...`
     * declaration inside the matching `[table]` section. Reads via VFS.
     */
    fun findDbColumn(project: Project, table: String, column: String): Rs2ConfigDeclTarget? {
        val contentDir = project.guessProjectDir()?.findFileByRelativePath("content") ?: return null
        return searchDbtableColumn(contentDir, project, table, column)
    }

    private fun searchDbtableColumn(dir: VirtualFile, project: Project, table: String, column: String): Rs2ConfigDeclTarget? {
        for (child in dir.children) {
            if (child.isDirectory) {
                searchDbtableColumn(child, project, table, column)?.let { return it }
            } else if (child.extension == "dbtable") {
                val text = runCatching { VfsUtilCore.loadText(child) }.getOrNull() ?: continue
                val lines = text.lines()
                var inTable = false
                for ((index, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        inTable = trimmed == "[$table]"
                        continue
                    }
                    if (inTable && trimmed.startsWith("column=")) {
                        val colName = trimmed.removePrefix("column=").substringBefore(',').trim()
                        if (colName == column) {
                            val colPos = line.indexOf(column)
                            val offset = lines.take(index).sumOf { it.length + 1 } + (if (colPos >= 0) colPos else 0)
                            return Rs2ConfigDeclTarget(project, child, offset, column)
                        }
                    }
                }
            }
        }
        return null
    }

    /** Expand [word] at [wordOffset] across `:`/`+` joiners (e.g. `questlist:chompybird`). */
    fun buildFullEntityName(text: CharSequence, wordOffset: Int, word: String): String {
        var start = wordOffset
        var end = wordOffset + word.length
        while (start > 0 && (text[start - 1] == ':' || text[start - 1] == '+')) {
            var s = start - 2
            while (s >= 0 && isEntityChar(text[s])) s--
            start = s + 1
        }
        while (end < text.length && (text[end] == ':' || text[end] == '+')) {
            var e = end + 1
            while (e < text.length && isEntityChar(text[e])) e++
            end = e
        }
        return text.subSequence(start, end).toString()
    }

    private fun indexOfIn(text: CharSequence, start: Int, end: Int, c: Char): Int {
        var i = start
        while (i < end) {
            if (text[i] == c) return i
            i++
        }
        return -1
    }

    private fun allDigits(text: CharSequence, start: Int, end: Int): Boolean {
        var i = start
        var seen = false
        while (i < end) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t') { i++; continue }
            if (!ch.isDigit()) return false
            seen = true
            i++
        }
        return seen
    }
}
