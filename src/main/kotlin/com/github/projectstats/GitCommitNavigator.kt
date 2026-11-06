package com.github.projectstats

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.vcs.log.impl.HashImpl
import com.intellij.vcs.log.impl.VcsLogNavigationUtil

/**
 * Opens a commit in the IDE's Git tool window (Log tab), selecting it.
 *
 * Uses [VcsLogNavigationUtil.jumpToRevisionAsync], the public entry point for this in the
 * platform's VCS Log module — it opens the log tab (creating one if needed) and jumps to the
 * given revision.
 */
object GitCommitNavigator {

    private val LOG = Logger.getInstance(GitCommitNavigator::class.java)

    fun openCommit(project: Project, repoRoot: String, sha: String) {
        val root = LocalFileSystem.getInstance().findFileByPath(repoRoot)
        if (root == null) {
            LOG.info("Could not resolve git root $repoRoot to open commit $sha")
            return
        }
        val hash = HashImpl.build(sha)
        ApplicationManager.getApplication().invokeLater {
            VcsLogNavigationUtil.jumpToRevisionAsync(project, root, hash, null)
        }
    }
}
