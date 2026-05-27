package rs2.lsp

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

class Rs2FileType private constructor() : LanguageFileType(Rs2Language) {
    companion object {
        @JvmField
        val INSTANCE = Rs2FileType()
        val ICON: Icon = IconUtil.scale(IconLoader.getIcon("/icons/rs2.png", Rs2FileType::class.java), null, 0.375f)
    }

    override fun getName(): String = "RS2"
    override fun getDescription(): String = "RS2 script file"
    override fun getDefaultExtension(): String = "rs2"
    override fun getIcon(): Icon = ICON
}
