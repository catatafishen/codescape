package com.github.projectstats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatsAggregatorTest {

    private fun file(
        path: String,
        language: String = "Kotlin",
        ext: String = "kt",
        module: String = "plugin-core",
        category: SourceCategory = SourceCategory.SOURCE,
        total: Int = 10,
        nonBlank: Int = 8,
        code: Int = 7,
        complexity: Int = 2,
        size: Long = 100,
        commits: Int = 1,
    ) = FileStat(path, language, ext, module, category, total, nonBlank, code, complexity, size, commits)

    private fun scan(vararg files: FileStat) = ScanResult(
        files = files.toList(),
        totalLines = files.sumOf { it.totalLines.toLong() },
        nonBlankLines = files.sumOf { it.nonBlankLines.toLong() },
        codeLines = files.sumOf { it.codeLines.toLong() },
        complexity = files.sumOf { it.complexity.toLong() },
        sizeBytes = files.sumOf { it.sizeBytes },
        fileCount = files.size.toLong(),
        commitCount = files.sumOf { it.commitCount.toLong() },
        scannedMillis = 1L,
    )

    @Test
    fun `group by language sums per language and sorts descending by totalLines`() {
        val r = scan(
            file("a.kt", language = "Kotlin", total = 10),
            file("b.kt", language = "Kotlin", total = 20),
            file("c.java", language = "Java", ext = "java", total = 50),
        )
        val groups = StatsAggregator.aggregate(r, GroupBy.LANGUAGE, true, true, true, true)
        assertEquals(listOf("Java", "Kotlin"), groups.map { it.key })
        assertEquals(50L, groups[0].totalLines)
        assertEquals(30L, groups[1].totalLines)
        assertEquals(2L, groups[1].fileCount)
    }

    @Test
    fun `group by module aggregates by module name`() {
        val r = scan(
            file("a.kt", module = "core"),
            file("b.kt", module = "core"),
            file("c.kt", module = "ui"),
        )
        val groups = StatsAggregator.aggregate(r, GroupBy.MODULE, true, true, true, true)
        assertEquals(setOf("core", "ui"), groups.map { it.key }.toSet())
        assertEquals(2L, groups.first { it.key == "core" }.fileCount)
    }

    @Test
    fun `group by category aggregates by display name`() {
        val r = scan(
            file("a.kt", category = SourceCategory.SOURCE),
            file("b.kt", category = SourceCategory.TEST),
        )
        val groups = StatsAggregator.aggregate(r, GroupBy.CATEGORY, true, true, true, true)
        assertEquals(setOf("Sources", "Tests"), groups.map { it.key }.toSet())
    }

    @Test
    fun `filters exclude tests resources generated and other respectively`() {
        val r = scan(
            file("src.kt", category = SourceCategory.SOURCE),
            file("test.kt", category = SourceCategory.TEST),
            file("test-res", category = SourceCategory.TEST_RESOURCES),
            file("res", category = SourceCategory.RESOURCES),
            file("gen", category = SourceCategory.GENERATED),
            file("misc", category = SourceCategory.OTHER),
        )
        // Only source
        val onlySource = StatsAggregator.aggregate(r, GroupBy.CATEGORY, false, false, false, false)
        assertEquals(listOf("Sources"), onlySource.map { it.key })

        // Include tests (TEST and TEST_RESOURCES toggled together)
        val withTests = StatsAggregator.aggregate(r, GroupBy.CATEGORY, true, false, false, false)
        assertEquals(setOf("Sources", "Tests", "Test Resources"), withTests.map { it.key }.toSet())

        // Include generated
        val withGen = StatsAggregator.aggregate(r, GroupBy.CATEGORY, false, true, false, false)
        assertEquals(setOf("Sources", "Generated"), withGen.map { it.key }.toSet())

        // Include resources
        val withRes = StatsAggregator.aggregate(r, GroupBy.CATEGORY, false, false, true, false)
        assertEquals(setOf("Sources", "Resources"), withRes.map { it.key }.toSet())

        // Include other
        val withOther = StatsAggregator.aggregate(r, GroupBy.CATEGORY, false, false, false, true)
        assertEquals(setOf("Sources", "Other"), withOther.map { it.key }.toSet())
    }

    @Test
    fun `directory tree builds nested StatGroups with accurate aggregates`() {
        val r = scan(
            file("src/main/A.kt", total = 10, nonBlank = 8, code = 7, size = 100, commits = 3),
            file("src/main/B.kt", total = 20, nonBlank = 18, code = 16, size = 200, commits = 1),
            file("src/test/C.kt", total = 5, nonBlank = 5, code = 5, size = 50, commits = 2),
            file("README.md", total = 4, nonBlank = 3, code = 3, size = 40, commits = 5),
        )
        val roots = StatsAggregator.aggregate(r, GroupBy.DIRECTORY, true, true, true, true)
        // Top-level children: "src" and "README.md"
        assertEquals(setOf("src", "README.md"), roots.map { it.key }.toSet())
        val src = roots.first { it.key == "src" }
        assertEquals(35L, src.totalLines)
        assertEquals(31L, src.nonBlankLines)
        assertEquals(28L, src.codeLines)
        assertEquals(350L, src.sizeBytes)
        assertEquals(3L, src.fileCount)
        assertEquals(6L, src.commitCount)
        assertEquals(setOf("main", "test"), src.children.map { it.key }.toSet())

        val main = src.children.first { it.key == "main" }
        assertEquals(30L, main.totalLines)
        assertEquals(2L, main.fileCount)
        assertEquals(setOf("A.kt", "B.kt"), main.children.map { it.key }.toSet())

        // Sorted by totalLines descending
        assertEquals("B.kt", main.children.first().key)
    }

    @Test
    fun `StatGroup value dispatches per Metric`() {
        val g = StatGroup("x", 10, 9, 8, 7, 100, 3, 5)
        assertEquals(10L, g.value(Metric.LOC))
        assertEquals(9L, g.value(Metric.NON_BLANK_LOC))
        assertEquals(8L, g.value(Metric.CODE_LOC))
        assertEquals(7L, g.value(Metric.COMPLEXITY))
        assertEquals(100L, g.value(Metric.SIZE))
        assertEquals(3L, g.value(Metric.FILE_COUNT))
        assertEquals(5L, g.value(Metric.COMMIT_COUNT))
    }

    @Test
    fun `empty scan produces empty groups`() {
        val r = scan()
        for (gb in GroupBy.values()) {
            val groups = StatsAggregator.aggregate(r, gb, true, true, true, true)
            assertTrue(groups.isEmpty(), "expected empty for $gb")
        }
    }

    @Test
    fun `Metric display strings match toString`() {
        assertEquals("Total LOC", Metric.LOC.toString())
        assertEquals("Complexity", Metric.COMPLEXITY.toString())
        assertEquals("Commits", Metric.COMMIT_COUNT.toString())
    }

    @Test
    fun `GroupBy display strings match toString`() {
        assertEquals("Language", GroupBy.LANGUAGE.toString())
        assertEquals("Directory tree", GroupBy.DIRECTORY.toString())
    }

    @Test
    fun `SourceCategory display values are populated`() {
        for (c in SourceCategory.values()) {
            assertNotNull(c.display)
            assertTrue(c.display.isNotBlank())
        }
        assertNull(null as String?) // sanity
    }
}
