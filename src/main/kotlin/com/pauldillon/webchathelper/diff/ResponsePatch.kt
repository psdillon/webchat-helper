package com.pauldillon.webchathelper.diff

import com.intellij.openapi.diff.impl.patch.ApplyPatchStatus
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.PatchSyntaxException
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier

/** A patch from ChatGPT that can't be used. [message] is written for the user. */
class ResponsePatchException(message: String) : Exception(message)

/** The result of a patch on one file. [before] is null for a new file, [after] for a deleted one. */
class PatchedFile(val path: String, val before: String?, val after: String?)

/** Works out what a patch from one of ChatGPT's responses does, without changing anything (#XIO). */
object ResponsePatch {
    private val MISSING_NAMES = setOf(null, "", "/dev/null")

    /**
     * The files [patch] changes. [contentOf] gives the text of the project file at a project-relative path,
     * or null if there is no such text file.
     */
    fun apply(patch: String, contentOf: (String) -> String?): List<PatchedFile> {
        val patches = try {
            PatchReader(patch).readTextPatches()
        } catch (e: PatchSyntaxException) {
            throw ResponsePatchException("ChatGPT's patch can't be read (${e.message}).")
        }
        if (patches.isEmpty()) throw ResponsePatchException("ChatGPT's patch doesn't change any files.")
        return patches.map { applyToFile(it, contentOf) }
    }

    private fun applyToFile(patch: TextFilePatch, contentOf: (String) -> String?): PatchedFile {
        val path = projectPath(patch, contentOf)
        if (!ResponseZip.isSafe(path)) throw ResponsePatchException("ChatGPT's patch changes $path, which is outside the project.")
        val before = if (patch.isNewFile) null else {
            contentOf(path) ?: throw ResponsePatchException("ChatGPT's patch changes $path, which isn't a text file in the project.")
        }
        val after = if (patch.isDeletedFile) null else {
            val applied = GenericPatchApplier.apply(before.orEmpty(), patch.hunks)
            if (applied == null || applied.status != ApplyPatchStatus.SUCCESS) {
                throw ResponsePatchException("ChatGPT's patch doesn't fit the current $path.")
            }
            applied.patchedText
        }
        return PatchedFile(path, before, after)
    }

    /** The patch's path, without the `a/` or `b/` that patches usually add unless that is a real project path. */
    private fun projectPath(patch: TextFilePatch, contentOf: (String) -> String?): String {
        val name = patch.afterName.takeUnless { it in MISSING_NAMES } ?: patch.beforeName.takeUnless { it in MISSING_NAMES }
            ?: throw ResponsePatchException("ChatGPT's patch doesn't say which file it changes.")
        val prefixed = name.startsWith("a/") || name.startsWith("b/")
        return if (prefixed && contentOf(name) == null) name.drop(2) else name
    }
}
