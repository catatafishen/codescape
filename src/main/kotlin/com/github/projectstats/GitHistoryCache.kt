package com.github.projectstats

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.security.MessageDigest

/**
 * Persists parsed mainline git history (per repo root) across IDE restarts so repeat scans only
 * need to read commits newer than the last cached one. Uses plain Java object serialization —
 * simple, dependency-free, and a corrupted/incompatible cache just degrades to a cache miss
 * (full rescan) rather than needing a versioned format.
 */
object GitHistoryCache {

    private val LOG = Logger.getInstance(GitHistoryCache::class.java)

    private fun cacheFile(repoRoot: String): File {
        val dir = File(PathManager.getSystemPath(), "codescape/history-cache")
        dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256").digest(repoRoot.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        // v2: history is now parsed with --no-renames; v1 caches hold rename-corrupted deltas.
        return File(dir, "$hex.v2.cache")
    }

    fun load(repoRoot: String): List<HistoryCommit>? {
        val file = cacheFile(repoRoot)
        if (!file.exists()) return null
        return try {
            ObjectInputStream(BufferedInputStream(FileInputStream(file))).use { stream ->
                @Suppress("UNCHECKED_CAST")
                stream.readObject() as? List<HistoryCommit>
            }
        } catch (e: Exception) {
            LOG.info("Could not read history cache for $repoRoot: ${e.message}")
            null
        }
    }

    fun save(repoRoot: String, commits: List<HistoryCommit>) {
        val file = cacheFile(repoRoot)
        try {
            ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { stream ->
                stream.writeObject(commits)
            }
        } catch (e: Exception) {
            LOG.info("Could not write history cache for $repoRoot: ${e.message}")
        }
    }
}
