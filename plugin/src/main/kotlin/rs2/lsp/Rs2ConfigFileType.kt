package rs2.lsp

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import javax.swing.Icon

class Rs2ConfigFileType private constructor() : LanguageFileType(Rs2ConfigLanguage) {
    companion object {
        @JvmField
        val INSTANCE = Rs2ConfigFileType()
        val ICON: Icon = IconUtil.scale(IconLoader.getIcon("/icons/pack.png", Rs2ConfigFileType::class.java), null, 0.375f)
    }

    override fun getName(): String = "RS2 Config"
    override fun getDescription(): String = "RuneScript 2 config / pack support file"
    override fun getDefaultExtension(): String = "pack"
    override fun getIcon(): Icon = ICON
}
