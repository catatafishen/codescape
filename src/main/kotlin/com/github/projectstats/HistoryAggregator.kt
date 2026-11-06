package com.github.projectstats

/**
 * Rolls up [HistoryCommit] file-level deltas into cumulative per-directory series for charting,
 * mirroring [StatsAggregator]'s directory-tree key convention (path split on `/`, each segment a
 * node keyed by its own name) so drill-down stays consistent with the treemap's directory view.
 */
object HistoryAggregator {

    /**
     * Cumulative series for the scope at [path] (empty = project root): the scope's own running
     * total, plus one series per immediate child (subdirectory or direct file) under it.
     */
    fun seriesFor(data: HistoryData, path: List<String>): HistorySeriesSet {
        val commits = data.commits
        val n = commits.size
        val totalArr = LongArray(n)
        val childArrs = LinkedHashMap<String, LongArray>()
        val childHasChildren = HashMap<String, Boolean>()
        var totalCum = 0L
        val childCum = HashMap<String, Long>()

        for (i in 0 until n) {
            for ((filePath, delta) in commits[i].fileDeltas) {
                if (delta == 0L) continue
                val parts = filePath.split('/').filter { it.isNotEmpty() }
                if (parts.size <= path.size) continue
                if (path.indices.any { parts[it] != path[it] }) continue

                totalCum += delta
                val childName = parts[path.size]
                childCum[childName] = (childCum[childName] ?: 0L) + delta
                if (parts.size > path.size + 2) childHasChildren[childName] = true
            }
            totalArr[i] = totalCum
            for ((name, cum) in childCum) {
                childArrs.getOrPut(name) { LongArray(n) }[i] = cum
            }
        }

        val children = childArrs.entries
            .map { (name, arr) -> ChildSeries(name, arr, childHasChildren[name] == true) }
            .sortedByDescending { it.values.lastOrNull() ?: 0L }
        return HistorySeriesSet(commits, totalArr, children)
    }
}
