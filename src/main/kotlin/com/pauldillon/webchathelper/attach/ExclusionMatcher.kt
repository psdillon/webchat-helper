package com.pauldillon.webchathelper.attach

/**
 * Decides which project files are not sent to ChatGPT, from the exclusion globs in settings (#AN7).
 * The globs use .gitignore format and are always relative to the project root (#8BN). The project's own
 * .gitignore files are not read (#2C0).
 *
 * Supported .gitignore rules: blank lines and `#` comments, `!` negation (the last matching line wins),
 * a trailing `/` matching only folders, a leading or middle `/` anchoring the pattern to the project
 * root, `*`, `?`, `[...]` ranges, `**` across folders, and `\` escapes. As in git, a path inside an
 * excluded folder is excluded, and a negation cannot bring it back.
 *
 * Paths given to the matcher are relative to the project root and use `/` separators.
 */
class ExclusionMatcher(globs: List<String>) {

    private class Rule(val regex: Regex, val negated: Boolean, val foldersOnly: Boolean)

    private val rules: List<Rule> = globs.mapNotNull(::parse)

    /** Whether [relativePath] is excluded. [isFolder] says whether the path is a folder. */
    fun isExcluded(relativePath: String, isFolder: Boolean): Boolean {
        val parts = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false // the project root itself
        // A path inside an excluded folder is excluded, whatever later rules say.
        for (i in 1 until parts.size) {
            if (matches(parts.subList(0, i).joinToString("/"), isFolder = true)) return true
        }
        return matches(parts.joinToString("/"), isFolder)
    }

    /** Whether the last rule matching [path] itself (not its parent folders) excludes it. */
    private fun matches(path: String, isFolder: Boolean): Boolean {
        for (rule in rules.asReversed()) {
            if (rule.foldersOnly && !isFolder) continue
            if (rule.regex.matches(path)) return !rule.negated
        }
        return false
    }

    private companion object {
        fun parse(line: String): Rule? {
            var pattern = trimTrailingSpaces(line)
            if (pattern.isEmpty() || pattern.startsWith("#")) return null
            val negated = pattern.startsWith("!")
            if (negated) pattern = pattern.substring(1)
            val foldersOnly = pattern.endsWith("/") && !pattern.endsWith("\\/")
            pattern = pattern.trimEnd('/')
            if (pattern.isEmpty()) return null
            // A slash anywhere but the end anchors the pattern to the project root; otherwise it
            // matches a file or folder name at any depth.
            val anchored = pattern.contains('/')
            pattern = pattern.removePrefix("/")
            if (!anchored) pattern = "**/$pattern"
            return Rule(Regex(toRegex(pattern)), negated, foldersOnly)
        }

        /** Trailing spaces are ignored unless escaped with a backslash. */
        fun trimTrailingSpaces(line: String): String {
            var end = line.length
            while (end > 0 && line[end - 1] == ' ' && !(end > 1 && line[end - 2] == '\\')) end--
            return line.substring(0, end)
        }

        fun toRegex(pattern: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    pattern.startsWith("**", i) && isWholeSegment(pattern, i) -> {
                        val atEnd = i + 2 == pattern.length
                        // "**/" matches zero or more folders; a final "/**" matches everything inside.
                        out.append(if (atEnd) ".*" else "(?:.*/)?")
                        i += if (atEnd) 2 else 3
                    }
                    c == '*' -> {
                        out.append("[^/]*")
                        while (i < pattern.length && pattern[i] == '*') i++
                    }
                    c == '?' -> { out.append("[^/]"); i++ }
                    c == '[' -> {
                        val end = classEnd(pattern, i)
                        if (end < 0) { out.append("\\["); i++ } else {
                            out.append(toCharClass(pattern.substring(i + 1, end)))
                            i = end + 1
                        }
                    }
                    c == '\\' && i + 1 < pattern.length -> { out.append(Regex.escape(pattern[i + 1].toString())); i += 2 }
                    else -> { out.append(Regex.escape(c.toString())); i++ }
                }
            }
            return out.toString()
        }

        /** Whether the `**` at [i] is a whole path segment: preceded by start or `/`, followed by end or `/`. */
        fun isWholeSegment(pattern: String, i: Int): Boolean =
            (i == 0 || pattern[i - 1] == '/') && (i + 2 == pattern.length || pattern[i + 2] == '/')

        /** Index of the `]` closing the range that starts at [start], or -1. */
        fun classEnd(pattern: String, start: Int): Int {
            var i = start + 1
            if (i < pattern.length && (pattern[i] == '!' || pattern[i] == '^')) i++
            if (i < pattern.length && pattern[i] == ']') i++ // a leading ']' is literal
            while (i < pattern.length && pattern[i] != ']') i++
            return if (i < pattern.length) i else -1
        }

        fun toCharClass(body: String): String {
            val negated = body.startsWith("!") || body.startsWith("^")
            val chars = if (negated) body.substring(1) else body
            val escaped = chars.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("&", "\\&")
            return if (negated) "[^/$escaped]" else "[$escaped&&[^/]]"
        }
    }
}
