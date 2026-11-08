package com.pauldillon.webchathelper.attach

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import kotlin.math.max
import kotlin.math.min
import kotlin.text.Charsets.ISO_8859_1
import kotlin.text.Charsets.UTF_8

/**
 * Formats one file's change as a plain unified diff (#0Y9). Paths are relative to the project root
 * (#VV0), so they have no `a/` and `b/` prefixes; `/dev/null` stands for the missing side of an added
 * or deleted file.
 *
 * Contents are treated as Latin-1, which maps each byte to one character and back, so the diff holds
 * each file's bytes unchanged whatever its encoding, as `diff` and git do.
 */
object UnifiedDiff {
    private const val CONTEXT_LINES = 3
    private const val NO_FILE = "/dev/null"
    private const val NO_NEWLINE = "\\ No newline at end of file\n"
    private val LINE_END = Regex("(?<=\n)")

    /** The diff of the file at [path] from [before] to [after]; null means the file doesn't exist on that side. */
    fun of(path: String, before: ByteArray?, after: ByteArray?): ByteArray {
        val shownPath = String(path.toByteArray(UTF_8), ISO_8859_1)
        val oldName = if (before == null) NO_FILE else shownPath
        val newName = if (after == null) NO_FILE else shownPath
        val diff = if (isBinary(before) || isBinary(after)) {
            "Binary files $oldName and $newName differ\n"
        } else {
            buildString {
                append("--- $oldName\n+++ $newName\n")
                appendHunks(lines(before), lines(after))
            }
        }
        return diff.toByteArray(ISO_8859_1)
    }

    /** Whether [content] holds a NUL byte, the test `diff` and git use for binary files. */
    private fun isBinary(content: ByteArray?) = content != null && content.any { it == 0.toByte() }

    /** Splits into lines, each keeping its linebreak, so a last line with no linebreak differs from one with it. */
    private fun lines(content: ByteArray?): Array<String> =
        String(content ?: ByteArray(0), ISO_8859_1).split(LINE_END).filter { it.isNotEmpty() }.toTypedArray()

    private fun edits(old: Array<String>, new: Array<String>): List<Diff.Change> = try {
        Diff.buildChanges(old, new)?.toList().orEmpty()
    } catch (_: FilesTooBigForDiffException) {
        listOf(Diff.Change(0, 0, old.size, new.size, null))
    }

    private val Diff.Change.oldEnd get() = line0 + deleted
    private val Diff.Change.newEnd get() = line1 + inserted

    private fun StringBuilder.appendHunks(old: Array<String>, new: Array<String>) {
        val edits = edits(old, new)
        var first = 0
        while (first < edits.size) {
            // Edits close enough for their context lines to touch share a hunk.
            var last = first
            while (last + 1 < edits.size && edits[last + 1].line0 - edits[last].oldEnd <= 2 * CONTEXT_LINES) last++

            val oldFrom = max(edits[first].line0 - CONTEXT_LINES, 0)
            val newFrom = edits[first].line1 - (edits[first].line0 - oldFrom)
            val oldTo = min(edits[last].oldEnd + CONTEXT_LINES, old.size)
            val newTo = edits[last].newEnd + (oldTo - edits[last].oldEnd)
            append("@@ -${range(oldFrom, oldTo)} +${range(newFrom, newTo)} @@\n")

            var position = oldFrom
            for (edit in edits.subList(first, last + 1)) {
                appendLines(' ', old, position, edit.line0)
                appendLines('-', old, edit.line0, edit.oldEnd)
                appendLines('+', new, edit.line1, edit.newEnd)
                position = edit.oldEnd
            }
            appendLines(' ', old, position, oldTo)
            first = last + 1
        }
    }

    /** A hunk header's range for lines [from] (0-based) up to [to]. An empty range names the line before it. */
    private fun range(from: Int, to: Int) = "${if (to > from) from + 1 else from},${to - from}"

    private fun StringBuilder.appendLines(prefix: Char, lines: Array<String>, from: Int, to: Int) {
        for (index in from until to) {
            append(prefix).append(lines[index])
            if (!lines[index].endsWith('\n')) append('\n').append(NO_NEWLINE)
        }
    }
}
