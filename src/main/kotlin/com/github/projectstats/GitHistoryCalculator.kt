package com.github.projectstats

import com.github.projectstats.GitHistoryCalculator.BATCH_SIZE
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Computes project size-over-time by walking `git log --first-parent --numstat` per discovered
 * git root and reconstructing per-commit net line deltas (added - deleted per touched file).
 *
 * `--first-parent` follows only the mainline (skips side-branch commits merged in), so a merge
 * doesn't double-count lines that already landed via the branch being merged. Combined with no
 * rename detection (`-M`), this is a simplification: renamed files show as a delete + an add
 * (a brief dip and recovery) rather than a clean move. Accepted trade-off for speed and simplicity.
 *
 * Results are cached on disk (see [GitHistoryCache]) so repeat invocations only read commits
 * newer than the last cached one. If the cached commit is no longer reachable from HEAD (history
 * rewritten via rebase/force-push), falls back to a full rescan for that root.
 */
object GitHistoryCalculator {

    private val LOG = Logger.getInstance(GitHistoryCalculator::class.java)
    private const val PROCESS_TIMEOUT_SECONDS = 600L

    /** Flush an in-progress batch to [onBatch] after this many newly-parsed commits. */
    private const val BATCH_SIZE = 100

    /** Marks the start of a commit header line in the `git log` output (never appears in file paths). */
    private const val HEADER_MARKER = '\u0001'

    /**
     * @param stopRequested polled between commits; when set, the scan stops early (its git
     *   subprocess is killed) but everything parsed so far is still cached and returned — a
     *   pause, not a discard. The next call resumes from the last fully-parsed commit.
     * @param onBatch invoked on this (background) thread every [BATCH_SIZE] newly-parsed commits,
     *   and once more at the end, with the full merged+sorted commit list accumulated so far
     *   across all repo roots — lets the UI render progressively during a long scan.
     */
    fun compute(
        project: Project,
        indicator: ProgressIndicator?,
        stopRequested: AtomicBoolean,
        onBatch: (List<HistoryCommit>) -> Unit,
    ): List<HistoryCommit> {
        val roots = discoverGitRoots(project)
        if (roots.isEmpty()) return emptyList()

        val perRoot = LinkedHashMap<String, MutableList<HistoryCommit>>()
        for (root in roots) perRoot[root] = GitHistoryCache.load(root)?.toMutableList() ?: mutableListOf()

        fun merged(): List<HistoryCommit> = perRoot.values.flatten().sortedBy { it.epochSeconds }

        for (root in roots) {
            if (stopRequested.get()) break
            indicator?.text2 = "Reading commit history for $root"
            try {
                readHistory(root, perRoot.getValue(root), indicator, stopRequested) { onBatch(merged()) }
            } catch (e: Throwable) {
                if (e is InterruptedException || e is ProcessCanceledException) throw e
                LOG.info("git log --numstat failed for $root: ${e.message}")
            } finally {
                GitHistoryCache.save(root, perRoot.getValue(root))
            }
        }
        val result = merged()
        onBatch(result)
        return result
    }

    private fun discoverGitRoots(project: Project): List<String> {
        val rootManager = ProjectRootManager.getInstance(project)
        val candidates = LinkedHashSet<String>()
        project.basePath?.let { candidates += it }
        for (cr in rootManager.contentRoots) candidates += cr.path
        val roots = LinkedHashSet<String>()
        for (c in candidates) findGitRoot(c)?.let { roots += it }
        return roots.toList()
    }

    private fun findGitRoot(startPath: String): String? {
        var dir: File? = File(startPath)
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir.absolutePath.replace('\\', '/')
            dir = dir.parentFile
        }
        return null
    }

    /** Appends newly-discovered commits (oldest to newest) directly into [accum]. */
    private fun readHistory(
        repoRoot: String,
        accum: MutableList<HistoryCommit>,
        indicator: ProgressIndicator?,
        stopRequested: AtomicBoolean,
        onBatch: () -> Unit,
    ) {
        val lastSha = accum.lastOrNull()?.sha
        val range = if (lastSha != null && isAncestor(repoRoot, lastSha)) {
            "$lastSha..HEAD"
        } else {
            accum.clear()
            "HEAD"
        }
        val proc = startGitLog(repoRoot, range) ?: return
        parseInto(proc, repoRoot, accum, indicator, stopRequested, onBatch)
    }

    /** True if [sha] is still an ancestor of HEAD (cache is safe to extend incrementally). */
    private fun isAncestor(repoRoot: String, sha: String): Boolean {
        return try {
            val proc = ProcessBuilder("git", "-C", repoRoot, "merge-base", "--is-ancestor", sha, "HEAD").start()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            val ok = finished && proc.exitValue() == 0
            if (proc.isAlive) proc.destroyForcibly()
            ok
        } catch (e: IOException) {
            false
        }
    }

    private fun startGitLog(repoRoot: String, range: String): Process? {
        val pb = ProcessBuilder(
            "git",
            "-c", "core.quotePath=false",
            "-C", repoRoot,
            "log",
            "--first-parent",
            "--reverse",
            "--numstat",
            "--pretty=format:$HEADER_MARKER%H %ct",
            range,
        )
        pb.redirectErrorStream(false)
        return try {
            pb.start()
        } catch (e: IOException) {
            LOG.info("Could not start git for $repoRoot: ${e.message}")
            null
        }
    }

    private fun parseInto(
        proc: Process,
        repoRoot: String,
        accum: MutableList<HistoryCommit>,
        indicator: ProgressIndicator?,
        stopRequested: AtomicBoolean,
        onBatch: () -> Unit,
    ) {
        try {
            var reachedEnd = false
            var stopped = false
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var sha: String? = null
                var epoch = 0L
                var deltas: LinkedHashMap<String, Long>? = null
                var sinceLastBatch = 0
                var lines = 0

                fun flush() {
                    val s = sha ?: return
                    accum += HistoryCommit(s, epoch, deltas?.toMap() ?: emptyMap(), repoRoot)
                    sha = null
                    deltas = null
                    if (++sinceLastBatch >= BATCH_SIZE) {
                        sinceLastBatch = 0
                        onBatch()
                    }
                }

                while (true) {
                    if (stopRequested.get()) {
                        stopped = true
                        break
                    }
                    val line = reader.readLine()
                    if (line == null) {
                        reachedEnd = true
                        break
                    }
                    if (line.startsWith(HEADER_MARKER)) {
                        flush()
                        val header = line.substring(1)
                        val sp = header.indexOf(' ')
                        sha = if (sp > 0) header.substring(0, sp) else header
                        epoch = if (sp > 0) header.substring(sp + 1).trim().toLongOrNull() ?: 0L else 0L
                        deltas = LinkedHashMap()
                    } else if (line.isNotEmpty()) {
                        val tab1 = line.indexOf('\t')
                        val tab2 = if (tab1 >= 0) line.indexOf('\t', tab1 + 1) else -1
                        if (tab1 > 0 && tab2 > tab1) {
                            val added = line.substring(0, tab1).toLongOrNull()
                            val removed = line.substring(tab1 + 1, tab2).toLongOrNull()
                            // "-\t-\tpath" marks a binary file; added/removed parse to null and are skipped.
                            if (added != null && removed != null) {
                                val path = line.substring(tab2 + 1)
                                val net = added - removed
                                if (net != 0L) deltas?.merge(path, net, Long::plus)
                            }
                        }
                    }
                    if (++lines and 0x3FFF == 0) indicator?.checkCanceled()
                }
                if (reachedEnd) flush()
            }
            if (stopped) {
                proc.destroyForcibly()
            } else if (!proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                LOG.info("git log timed out for $repoRoot after ${PROCESS_TIMEOUT_SECONDS}s")
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }
}
