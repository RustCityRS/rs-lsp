package rs2.lsp

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class Rs2ConfigFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, Rs2ConfigLanguage) {
    override fun getFileType(): FileType = Rs2ConfigFileType.INSTANCE
    override fun toString(): String = "RS2 Config File"
}
