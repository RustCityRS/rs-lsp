package rs2.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/** Kind of a config/pack declaration, ordered by navigation preference (lower wins). */
object Rs2DeclKind {
    const val SECTION = 0   // `[name]` header in a config file (.obj/.npc/.dbtable/…)
    const val PACK = 1      // `id=name` entry in a .pack file
    const val CONSTANT = 2  // `^name = value` / `name = value` in a .constant file
}

/** One declaration occurrence inside a file: its char offset and [Rs2DeclKind]. */
data class Rs2DeclOccurrence(val offset: Int, val kind: Int)

/**
 * A size-independent index of config/pack declaration names -> occurrences.
 *
 * Built straight from file content (FileBasedIndex's ~20 MB content cap, not the
 * 2.5 MB `idea.max.intellisense.filesize` PSI cap), so declarations inside large
 * `all.*` dumps are resolvable even though IntelliJ disables PSI for those files.
 * Incremental: only changed files are re-indexed, and the data persists across
 * restarts — replacing the previous recursive directory scans.
 *
 * Covers, by extension:
 *   - `.pack`     -> `id=name` entries  (PACK)  — entities, game vars
 *   - `.constant` -> `^name`/`name` LHS (CONSTANT)
 *   - other config files -> `[name]` headers (SECTION) — entities, dbtable tables
 *
 * dbtable *columns* (`column=name`) are resolved separately; those files are small.
 */
class Rs2ConfigDeclarationIndex : FileBasedIndexExtension<String, List<Rs2DeclOccurrence>>() {
    companion object {
        val NAME: ID<String, List<Rs2DeclOccurrence>> = ID.create("rs2.config.declarations")

        /**
         * All declaration occurrences of [name] in the project, ordered by
         * [Rs2DeclKind] preference (config `[section]` before `.pack` entry).
         */
        fun find(project: Project, name: String): List<Pair<VirtualFile, Rs2DeclOccurrence>> {
            if (name.isBlank()) return emptyList()
            val scope = com.intellij.psi.search.GlobalSearchScope.allScope(project)
            val out = mutableListOf<Pair<VirtualFile, Rs2DeclOccurrence>>()
            // The index is a fast path only — never let a not-yet-ready index break
            // navigation; callers fall back to a VFS scan when this returns empty.
            try {
                FileBasedIndex.getInstance().processValues(NAME, name, null, { file, occurrences ->
                    for (occ in occurrences) out.add(file to occ)
                    true
                }, scope)
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
                return emptyList()
            }
            // Prefer the config `[section]` over the `.pack` entry, and a real
            // config file over a generated `_unpack` dump of the same declaration.
            out.sortWith(compareBy({ it.second.kind }, { it.first.path.contains("/_unpack/") }))
            return out
        }

        fun allNames(project: Project): Collection<String> =
            try {
                FileBasedIndex.getInstance().getAllKeys(NAME, project)
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
                emptyList()
            }
    }

    override fun getName(): ID<String, List<Rs2DeclOccurrence>> = NAME

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    // Index RS2Config files only under the project's own content/ tree. This keeps
    // reference checkouts (ref/, vendored servers, etc.) out of goto targets and
    // Search Everywhere — navigation stays scoped to this project's content.
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.ProjectSpecificInputFilter {
            override fun acceptInput(file: IndexedFile): Boolean {
                if (file.fileType !is Rs2ConfigFileType) return false
                val project = file.project ?: return false
                val content = project.guessProjectDir()?.findChild("content") ?: return false
                return VfsUtilCore.isAncestor(content, file.file, false)
            }
        }

    override fun getValueExternalizer(): DataExternalizer<List<Rs2DeclOccurrence>> =
        object : DataExternalizer<List<Rs2DeclOccurrence>> {
            override fun save(out: DataOutput, value: List<Rs2DeclOccurrence>) {
                DataInputOutputUtil.writeINT(out, value.size)
                for (v in value) {
                    DataInputOutputUtil.writeINT(out, v.offset)
                    out.writeByte(v.kind)
                }
            }

            override fun read(input: DataInput): List<Rs2DeclOccurrence> {
                val size = DataInputOutputUtil.readINT(input)
                val list = ArrayList<Rs2DeclOccurrence>(size)
                repeat(size) {
                    val offset = DataInputOutputUtil.readINT(input)
                    val kind = input.readByte().toInt()
                    list.add(Rs2DeclOccurrence(offset, kind))
                }
                return list
            }
        }

    override fun getIndexer(): DataIndexer<String, List<Rs2DeclOccurrence>, FileContent> =
        DataIndexer { inputData ->
            val ext = inputData.fileName.substringAfterLast('.', "")
            val mode = when (ext) {
                "pack" -> Rs2DeclKind.PACK
                "constant" -> Rs2DeclKind.CONSTANT
                "order", "hashes" -> return@DataIndexer emptyMap() // no navigable declarations
                else -> Rs2DeclKind.SECTION
            }

            val text = inputData.contentAsText
            val result = HashMap<String, MutableList<Rs2DeclOccurrence>>()
            val len = text.length
            var lineStart = 0
            var i = 0
            while (i <= len) {
                if (i == len || text[i] == '\n') {
                    parseLine(text, lineStart, i, mode, result)
                    lineStart = i + 1
                }
                i++
            }
            result
        }

    private fun parseLine(
        text: CharSequence,
        start: Int,
        end: Int,
        mode: Int,
        out: HashMap<String, MutableList<Rs2DeclOccurrence>>
    ) {
        // Skip leading horizontal whitespace.
        var s = start
        while (s < end && (text[s] == ' ' || text[s] == '\t' || text[s] == '\r')) s++
        if (s >= end) return
        if (text[s] == '/') return // comment line

        // `[name]` section header — a declaration in any config file.
        if (text[s] == '[') {
            var b = s + 1
            while (b < end && text[b] != ']') b++
            if (b < end && b > s + 1) {
                val name = text.subSequence(s + 1, b).toString().trim()
                if (name.isNotEmpty() && mode != Rs2DeclKind.PACK && mode != Rs2DeclKind.CONSTANT) {
                    add(out, name, Rs2DeclOccurrence(s + 1, Rs2DeclKind.SECTION))
                }
            }
            return
        }

        when (mode) {
            Rs2DeclKind.PACK -> {
                // `id=name` where id is all digits — a pack declaration.
                val eq = indexOf(text, s, end, '=')
                if (eq < 0) return
                if (!allDigits(text, s, eq)) return
                var v = eq + 1
                while (v < end && (text[v] == ' ' || text[v] == '\t')) v++
                val name = text.subSequence(v, end).toString().trim()
                if (name.isNotEmpty()) add(out, name, Rs2DeclOccurrence(v, Rs2DeclKind.PACK))
            }

            Rs2DeclKind.CONSTANT -> {
                // `^name = value` or `name = value`.
                val eq = indexOf(text, s, end, '=')
                if (eq < 0) return
                var ns = s
                if (text[ns] == '^') ns++
                var ne = eq
                while (ne > ns && (text[ne - 1] == ' ' || text[ne - 1] == '\t')) ne--
                if (ne <= ns) return
                val name = text.subSequence(ns, ne).toString()
                add(out, name, Rs2DeclOccurrence(ns, Rs2DeclKind.CONSTANT))
            }
        }
    }

    private fun add(
        out: HashMap<String, MutableList<Rs2DeclOccurrence>>,
        name: String,
        occ: Rs2DeclOccurrence
    ) {
        out.getOrPut(name) { ArrayList(1) }.add(occ)
    }

    private fun indexOf(text: CharSequence, start: Int, end: Int, c: Char): Int {
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
