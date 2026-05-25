package rs2.lsp

import com.intellij.lang.Language

object Rs2Language : Language("RS2") {
    private fun readResolve(): Any = Rs2Language
}
