package rs2.lsp

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class Rs2File(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, Rs2Language) {
    override fun getFileType(): FileType = Rs2FileType.INSTANCE
}
