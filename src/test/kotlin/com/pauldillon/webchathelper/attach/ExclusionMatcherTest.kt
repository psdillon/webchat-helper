package com.pauldillon.webchathelper.attach

import com.pauldillon.webchathelper.settings.WebChatHelperSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionMatcherTest {

    private fun matcher(vararg globs: String) = ExclusionMatcher(globs.toList())

    private fun ExclusionMatcher.excludesFile(path: String) = isExcluded(path, isFolder = false)
    private fun ExclusionMatcher.excludesFolder(path: String) = isExcluded(path, isFolder = true)

    @Test
    fun defaults() { // #CI3
        val m = ExclusionMatcher(WebChatHelperSettings.DEFAULT_EXCLUSION_GLOBS)
        assertTrue(m.excludesFolder(".git"))
        assertTrue(m.excludesFile(".git/config"))
        assertTrue(m.excludesFile(".idea/workspace.xml"))
        assertTrue(m.excludesFile(".env"))
        assertTrue(m.excludesFile("sub/.env"))
        assertTrue(m.excludesFile("build/libs/a.jar"))
        assertTrue(m.excludesFile("module/build/out.txt"))
        assertTrue(m.excludesFile("target/x.class"))
        assertFalse(m.excludesFile("build")) // "build/" matches folders only
        assertFalse(m.excludesFile("src/main/Build.kt"))
        assertFalse(m.excludesFile(".envrc"))
        assertFalse(m.excludesFile("src/output/a.txt"))
    }

    @Test
    fun unanchoredNameMatchesAtAnyDepth() {
        val m = matcher("*.log")
        assertTrue(m.excludesFile("a.log"))
        assertTrue(m.excludesFile("x/y/a.log"))
        assertFalse(m.excludesFile("a.log.txt"))
    }

    @Test
    fun slashAnchorsToRoot() { // #8BN
        val m = matcher("/docs", "src/gen/")
        assertTrue(m.excludesFolder("docs"))
        assertTrue(m.excludesFile("docs/readme.md"))
        assertFalse(m.excludesFile("sub/docs/readme.md"))
        assertTrue(m.excludesFile("src/gen/A.kt"))
        assertFalse(m.excludesFile("other/src/gen/A.kt"))
    }

    @Test
    fun wildcards() {
        val m = matcher("a?c", "[0-9]x", "[!q]z", "logs/*.txt")
        assertTrue(m.excludesFile("abc"))
        assertFalse(m.excludesFile("a/c"))
        assertTrue(m.excludesFile("5x"))
        assertFalse(m.excludesFile("ax"))
        assertTrue(m.excludesFile("az"))
        assertFalse(m.excludesFile("qz"))
        assertTrue(m.excludesFile("logs/a.txt"))
        assertFalse(m.excludesFile("logs/sub/a.txt"))
    }

    @Test
    fun doubleStar() {
        val m = matcher("**/temp", "a/**/b", "out2/**")
        assertTrue(m.excludesFile("temp"))
        assertTrue(m.excludesFile("x/y/temp"))
        assertTrue(m.excludesFile("a/b"))
        assertTrue(m.excludesFile("a/x/y/b"))
        assertTrue(m.excludesFile("out2/x/y"))
        assertFalse(m.excludesFolder("out2"))
    }

    @Test
    fun negationLastMatchWins() {
        val m = matcher("*.kt", "!Keep.kt")
        assertTrue(m.excludesFile("A.kt"))
        assertFalse(m.excludesFile("src/Keep.kt"))
    }

    @Test
    fun negationCannotReincludeInsideExcludedFolder() {
        val m = matcher("gen/", "!gen/keep.txt")
        assertTrue(m.excludesFile("gen/keep.txt"))
    }

    @Test
    fun commentsBlanksAndEscapes() {
        val m = matcher("# comment", "", "   ", "\\#hash", "\\!bang", "trail\\ ")
        assertFalse(m.excludesFile("# comment"))
        assertTrue(m.excludesFile("#hash"))
        assertTrue(m.excludesFile("!bang"))
        assertTrue(m.excludesFile("trail "))
    }

    @Test
    fun projectRootIsNeverExcluded() {
        assertFalse(matcher("*").excludesFolder(""))
    }
}
