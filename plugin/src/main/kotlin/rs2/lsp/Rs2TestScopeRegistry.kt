package rs2.lsp

import com.intellij.openapi.project.Project
import java.io.File

object Rs2TestScopeRegistry {
    private val cache = mutableMapOf<String, Set<String>>()

    fun getTestScopedScripts(project: Project): Set<String> {
        val key = project.basePath ?: return emptySet()
        return cache.getOrPut(key) { scanTestScripts(project) }
    }

    fun invalidate(project: Project) {
        cache.remove(project.basePath)
    }

    private fun scanTestScripts(project: Project): Set<String> {
        val basePath = project.basePath ?: return emptySet()
        val scriptsDir = File(basePath, "content/scripts")
        if (!scriptsDir.exists()) return emptySet()

        val testScoped = mutableSetOf<String>()
        scriptsDir.walkTopDown().filter { it.extension == "rs2" }.forEach { file ->
            val text = try { file.readText() } catch (_: Exception) { return@forEach }
            val markerPos = if (text.startsWith("#testscript")) 0
                else text.indexOf("\n#testscript").let { if (it < 0) return@forEach else it }

            val testSection = text.substring(markerPos)
            val procRegex = Regex("""\[proc,(\w+)]""")
            val labelRegex = Regex("""\[label,(\w+)]""")

            procRegex.findAll(testSection).forEach { testScoped.add(it.groupValues[1]) }
            labelRegex.findAll(testSection).forEach { testScoped.add(it.groupValues[1]) }
        }
        return testScoped
    }

    private val TEST_COMMAND_PREFIXES = listOf("assert_", "test_", "setup_", "verify_", "wait_for")

    fun isTestCommand(name: String): Boolean {
        return TEST_COMMAND_PREFIXES.any { name.startsWith(it) }
    }
}
