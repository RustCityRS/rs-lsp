package rs2.lsp

import com.intellij.lexer.LexerBase
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
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

/**
 * A content/-scoped index of every identifier occurrence (name -> char offsets)
 * across `.rs2` scripts and RS2Config files — section headers, pack entries,
 * value references, script identifiers. Lets Find Usages be an instant index
 * lookup instead of re-scanning the whole tree (and re-reading the multi-MB
 * `all.*` dumps) on each invocation.
 *
 * Built by running the same lexers the editor uses, so occurrences inside strings
 * and comments are excluded for free. Offsets point at the bare name (sigils like
 * `~ @ ^ % $` are stripped), so a query with the plain name lines up.
 */
class Rs2ReferenceIndex : FileBasedIndexExtension<String, List<Int>>() {
    companion object {
        val NAME: ID<String, List<Int>> = ID.create("rs2.references")

        /** All `(file, offset)` occurrences of [name] across the project's content/. */
        fun find(project: Project, name: String): List<Pair<VirtualFile, Int>> {
            if (name.isBlank()) return emptyList()
            val out = mutableListOf<Pair<VirtualFile, Int>>()
            try {
                FileBasedIndex.getInstance().processValues(NAME, name, null, { file, offsets ->
                    for (o in offsets) out.add(file to o)
                    true
                }, GlobalSearchScope.allScope(project))
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (_: com.intellij.openapi.project.IndexNotReadyException) {
                return emptyList()
            }
            return out
        }

        val RS2_REF_TYPES: Set<IElementType> = setOf(
            Rs2TokenTypes.IDENTIFIER,
            Rs2TokenTypes.TRIGGER_SUBJECT,
            Rs2TokenTypes.TRIGGER_SCRIPT_NAME,
            Rs2TokenTypes.PROC_CALL,
            Rs2TokenTypes.JUMP_CALL,
            Rs2TokenTypes.CONSTANT,
            Rs2TokenTypes.GAME_VAR,
        )
        val CONFIG_REF_TYPES: Set<IElementType> = setOf(
            Rs2ConfigTokenTypes.SECTION_NAME,
            Rs2ConfigTokenTypes.PACK_NAME,
            Rs2ConfigTokenTypes.IDENTIFIER,
            Rs2ConfigTokenTypes.CONSTANT,
            Rs2ConfigTokenTypes.GAME_VAR,
        )
    }

    override fun getName(): ID<String, List<Int>> = NAME

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : FileBasedIndex.ProjectSpecificInputFilter {
            override fun acceptInput(file: IndexedFile): Boolean {
                val ft = file.fileType
                if (ft !is Rs2FileType && ft !is Rs2ConfigFileType) return false
                val project = file.project ?: return false
                val content = project.guessProjectDir()?.findChild("content") ?: return false
                return VfsUtilCore.isAncestor(content, file.file, false)
            }
        }

    override fun getValueExternalizer(): DataExternalizer<List<Int>> =
        object : DataExternalizer<List<Int>> {
            override fun save(out: DataOutput, value: List<Int>) {
                DataInputOutputUtil.writeINT(out, value.size)
                for (v in value) DataInputOutputUtil.writeINT(out, v)
            }

            override fun read(input: DataInput): List<Int> {
                val size = DataInputOutputUtil.readINT(input)
                val list = ArrayList<Int>(size)
                repeat(size) { list.add(DataInputOutputUtil.readINT(input)) }
                return list
            }
        }

    override fun getIndexer(): DataIndexer<String, List<Int>, FileContent> =
        DataIndexer { inputData ->
            val text = inputData.contentAsText
            val result = HashMap<String, MutableList<Int>>()
            if (inputData.fileName.endsWith(".rs2")) {
                collect(Rs2Lexer(), text, result, RS2_REF_TYPES)
            } else {
                collect(Rs2ConfigLexer(), text, result, CONFIG_REF_TYPES)
            }
            result
        }

    private fun collect(
        lexer: LexerBase,
        text: CharSequence,
        out: HashMap<String, MutableList<Int>>,
        accept: Set<IElementType>,
    ) {
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val tt = lexer.tokenType ?: break
            if (tt in accept) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                var i = start
                while (i < end && isSigil(text[i])) i++
                if (i < end) {
                    out.getOrPut(text.subSequence(i, end).toString()) { ArrayList(1) }.add(i)
                }
            }
            lexer.advance()
        }
    }

    private fun isSigil(c: Char): Boolean = c == '~' || c == '@' || c == '^' || c == '%' || c == '$'
}
