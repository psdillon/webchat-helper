package com.pauldillon.webchathelper.attach

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.pauldillon.webchathelper.settings.WebChatHelperSettings

/**
 * A project's files as the plugin sees them: paths relative to the project root (#VV0) and the
 * exclusion globs from settings (#AN7).
 */
class ProjectFiles(val root: VirtualFile, private val exclusions: ExclusionMatcher) {

    /** [file]'s path relative to the project root with `/` separators, or null if it is outside the project. */
    fun relativePath(file: VirtualFile): String? =
        if (file == root) "" else VfsUtilCore.getRelativePath(file, root, '/')

    fun isExcluded(file: VirtualFile): Boolean {
        val path = relativePath(file) ?: return false
        return exclusions.isExcluded(path, file.isDirectory)
    }

    /** The files and subfolders in a folder, after exclusions; see [contentsOf]. */
    class FolderContents(val files: List<VirtualFile>, val folders: List<VirtualFile>)

    /**
     * The files in [folder] and its subfolders, leaving out excluded files and the contents of excluded
     * folders (#AV2). Sorted by path.
     */
    fun filesIn(folder: VirtualFile): List<VirtualFile> = contentsOf(folder).files

    /**
     * The files and folders in [folder] and its subfolders, [folder] itself included in the folders,
     * leaving out excluded files and folders and their contents (#AV2). A folder whose contents are all
     * excluded is still listed, as an empty folder (#R58). Each list is sorted by path.
     */
    fun contentsOf(folder: VirtualFile): FolderContents {
        val files = mutableListOf<VirtualFile>()
        val folders = mutableListOf<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(folder, object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS) {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file != folder && isExcluded(file)) return false
                if (file.isDirectory) folders += file else files += file
                return true
            }
        })
        return FolderContents(files.sortedBy { relativePath(it) }, folders.sortedBy { relativePath(it) })
    }

    companion object {
        /** The project's files with the current exclusion settings, or null if the project has no root folder. */
        fun of(project: Project): ProjectFiles? {
            val root = project.guessProjectDir() ?: return null
            return ProjectFiles(root, ExclusionMatcher(WebChatHelperSettings.getInstance().state.exclusionGlobs))
        }
    }
}
