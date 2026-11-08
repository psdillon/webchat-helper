package com.pauldillon.webchathelper.toolwindow

import com.intellij.ide.util.gotoByName.DefaultFileNavigationContributor
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCoreUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/**
 * A [GotoFileModel] that lists folders alongside files whatever the search text, for the '@' dialog's
 * "always show folders" setting (#4QH). By default the platform lists folders only when the search text
 * ends with '/', and then lists nothing else; that still applies here.
 */
class GotoFileOrFolderModel(project: Project) : GotoFileModel(project) {

    // Not cached in a field: the superclass may call this while it is being constructed.
    override fun getContributorList(): List<ChooseByNameContributor> =
        super.getContributorList().map { if (it is DefaultFileNavigationContributor) FileOrFolderContributor(it) else it }
}

/**
 * Finds files and folders by name. [default] is the platform's contributor, which finds folders only when
 * the search text ends with '/'.
 */
private class FileOrFolderContributor(private val default: DefaultFileNavigationContributor) :
    ChooseByNameContributorEx, DumbAware {

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) =
        default.processNames(processor, scope, filter)

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val pattern = parameters.completePattern
        if (pattern.endsWith('/') || pattern.endsWith('\\')) {
            default.processElementsWithName(name, processor, parameters)
            return
        }
        // As the platform's contributor, but keeping folders.
        val psiManager = PsiManager.getInstance(parameters.project)
        val inLibraries = parameters.searchScope.isSearchInLibraries
        FilenameIndex.processFilesByNames(setOf(name), true, parameters.searchScope, parameters.idFilter) { file ->
            if (!file.isValid || (!inLibraries && ProjectCoreUtil.isProjectOrWorkspaceFile(file))) return@processFilesByNames true
            val item = if (file.isDirectory) psiManager.findDirectory(file) else psiManager.findFile(file)
            item == null || processor.process(item)
        }
    }
}
