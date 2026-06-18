package rs2.lsp

import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement

/**
 * A navigable, PSI-free handle to a config/pack declaration located by
 * [Rs2ConfigDeclarationIndex] — `(name, file, offset)`. Navigation goes through
 * [OpenFileDescriptor], so it works regardless of the file's size (even when
 * IntelliJ has disabled PSI for it). Doubles as the target for the symbol
 * contributor and as the Find Usages anchor (see [Rs2FindUsagesHandlerFactory]).
 */
class Rs2ConfigDeclTarget(
    private val project: Project,
    val virtualFile: VirtualFile,
    private val offset: Int,
    private val declName: String,
) : FakePsiElement() {

    override fun getProject(): Project = project

    override fun getContainingFile(): PsiFile? =
        if (virtualFile.isValid) PsiManager.getInstance(project).findFile(virtualFile) else null

    override fun getParent(): PsiElement? = containingFile

    override fun getName(): String = declName

    override fun getText(): String = declName

    override fun getTextOffset(): Int = offset

    override fun isValid(): Boolean = virtualFile.isValid

    override fun isPhysical(): Boolean = true

    override fun canNavigate(): Boolean = virtualFile.isValid

    override fun canNavigateToSource(): Boolean = virtualFile.isValid

    override fun navigate(requestFocus: Boolean) {
        if (virtualFile.isValid) {
            OpenFileDescriptor(project, virtualFile, offset).navigate(requestFocus)
        }
    }

    override fun getPresentation(): ItemPresentation =
        PresentationData(declName, virtualFile.path, Rs2ConfigFileType.ICON, null)

    override fun toString(): String = "Rs2ConfigDeclTarget($declName)"

    override fun equals(other: Any?): Boolean =
        other is Rs2ConfigDeclTarget &&
            other.virtualFile == virtualFile &&
            other.offset == offset

    override fun hashCode(): Int = virtualFile.hashCode() * 31 + offset
}
