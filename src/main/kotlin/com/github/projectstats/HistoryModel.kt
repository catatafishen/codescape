package com.github.projectstats

import java.io.Serializable

/**
 * One mainline commit's net line-delta per touched file, as reported by
 * `git log --first-parent --numstat`. Deltas are (added - deleted); binary files (numstat
 * reports "-\t-") contribute zero. Paths use forward slashes and are relative to the repo root.
 */
data class HistoryCommit(
    val sha: String,
    val epochSeconds: Long,
    val fileDeltas: Map<String, Long>,
) : Serializable

/** Merged, chronologically-sorted mainline commit history across all discovered git roots. */
data class HistoryData(val commits: List<HistoryCommit>)

/** Cumulative size series for one immediate child (subdirectory or file) of a drilled-into scope. */
data class ChildSeries(
    val name: String,
    val values: LongArray,
    /** True if this child itself contains further nested paths (i.e. it's a directory worth drilling into). */
    val hasChildren: Boolean,
)

/** Cumulative size-over-time series for one directory scope, ready for charting. */
data class HistorySeriesSet(
    val commits: List<HistoryCommit>,
    /** Cumulative total for the scope itself, aligned index-for-index with [commits]. */
    val total: LongArray,
    /** Per immediate child, sorted by final cumulative value descending. */
    val children: List<ChildSeries>,
)
