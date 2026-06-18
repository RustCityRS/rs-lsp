package rs2.lsp

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/**
 * Surfaces every config/pack declaration ([Rs2ConfigDeclarationIndex]) in
 * Navigate → Symbol and Search Everywhere. Because it is backed by the index and
 * navigates through [Rs2ConfigDeclTarget], declarations inside large `all.*`
 * dumps are reachable even though IntelliJ has disabled PSI for those files —
 * and the resulting target is a valid Find Usages anchor (see
 * [Rs2FindUsagesHandlerFactory]).
 */
class Rs2ConfigSymbolContributor : ChooseByNameContributorEx {
    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val project = scope.project ?: return
        for (name in Rs2ConfigDeclarationIndex.allNames(project)) {
            if (!processor.process(name)) return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        val project = parameters.project
        for ((file, occ) in Rs2ConfigDeclarationIndex.find(project, name)) {
            if (!processor.process(Rs2ConfigDeclTarget(project, file, occ.offset, name))) return
        }
    }
}
