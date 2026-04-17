package com.github.projectstats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LineCounterTest {

    @Test
    fun `empty text has zero non-blank lines`() {
        val s = LineCounter.count("", "kt")
        assertEquals(0, s.nonBlank)
        assertEquals(0, s.code)
        assertEquals(0, s.complexity)
    }

    @Test
    fun `single line without trailing newline counts as one`() {
        val s = LineCounter.count("val x = 1", "kt")
        assertEquals(1, s.total)
        assertEquals(1, s.nonBlank)
        assertEquals(1, s.code)
    }

    @Test
    fun `trailing newline does not add a phantom line`() {
        val s = LineCounter.count("a\nb\n", "kt")
        assertEquals(2, s.total)
        assertEquals(2, s.nonBlank)
        assertEquals(2, s.code)
    }

    @Test
    fun `blank lines counted in total but not non-blank`() {
        val s = LineCounter.count("a\n\n   \nb", "kt")
        assertEquals(4, s.total)
        assertEquals(2, s.nonBlank)
        assertEquals(2, s.code)
    }

    @Test
    fun `CRLF line endings handled`() {
        val s = LineCounter.count("a\r\nb\r\n", "kt")
        assertEquals(2, s.total)
        assertEquals(2, s.nonBlank)
    }

    @Test
    fun `line comments excluded from code for languages with comment styles`() {
        val src = """
            val x = 1
            // comment only
            val y = 2 // trailing comment
        """.trimIndent()
        val s = LineCounter.count(src, "kt")
        assertEquals(3, s.total)
        assertEquals(3, s.nonBlank)
        assertEquals(2, s.code)
    }

    @Test
    fun `block comments across multiple lines excluded from code`() {
        val src = """
            val a = 1
            /* start
               middle
               end */
            val b = 2
        """.trimIndent()
        val s = LineCounter.count(src, "kt")
        assertEquals(5, s.total)
        assertEquals(5, s.nonBlank)
        // only the two 'val' lines are code — block comment fully masks its content
        assertEquals(2, s.code)
    }

    @Test
    fun `python hash comments`() {
        val src = "# header\nx = 1\n# tail\n"
        val s = LineCounter.count(src, "py")
        assertEquals(3, s.total)
        assertEquals(1, s.code)
    }

    @Test
    fun `unknown extension treats every non-blank line as code`() {
        val s = LineCounter.count("one\ntwo\n\nfour", "unknownext")
        assertEquals(4, s.total)
        assertEquals(3, s.nonBlank)
        assertEquals(3, s.code)
        assertEquals(0, s.complexity)
    }

    @Test
    fun `kotlin complexity counts branching keywords`() {
        val src = """
            fun foo(x: Int): Int {
                if (x > 0) return 1
                for (i in 0..x) println(i)
                while (x < 10) {}
                return 0
            }
        """.trimIndent()
        val s = LineCounter.count(src, "kt")
        // if + for + while = 3
        assertEquals(3, s.complexity)
    }

    @Test
    fun `keywords inside comments do not contribute to complexity`() {
        val src = """
            // if for while
            /* if if if */
            val x = 1
        """.trimIndent()
        val s = LineCounter.count(src, "kt")
        assertEquals(0, s.complexity)
    }

    @Test
    fun `word boundary prevents false positives for keyword substrings`() {
        // "iffy", "forum", "whiley" should NOT count as if/for/while
        val src = "val iffy = forum + whileyVariable"
        val s = LineCounter.count(src, "kt")
        assertEquals(0, s.complexity)
    }

    @Test
    fun `case-sensitive comment styles and keywords`() {
        // Uppercase IF is not a keyword
        val s = LineCounter.count("val IF = 1", "kt")
        assertEquals(0, s.complexity)
    }
}
