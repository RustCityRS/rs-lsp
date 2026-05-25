package rs2.lsp

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class Rs2FileType private constructor() : LanguageFileType(Rs2Language) {
    companion object {
        @JvmField
        val INSTANCE = Rs2FileType()
    }

    override fun getName(): String = "RS2"

    override fun getDescription(): String = "RS2 script file"

    override fun getDefaultExtension(): String = "rs2"

    override fun getIcon(): Icon? = null
}
