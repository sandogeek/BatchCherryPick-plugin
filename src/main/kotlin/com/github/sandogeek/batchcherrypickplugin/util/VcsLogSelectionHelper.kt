package com.github.sandogeek.batchcherrypickplugin.util

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsLogCommitSelection
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** A single commit selected in the Git Log, resolved for read-only display. */
data class CommitInfo(
    val hash: String,
    val subject: String,
    val author: String,
    val timestamp: Long,
) {
    val shortHash: String get() = if (hash.length > 8) hash.substring(0, 8) else hash
}

/** The commits selected in the Git Log together with their owning repository. */
data class SelectedCommits(
    val repository: GitRepository,
    val commits: List<CommitInfo>,
) {
    val hashes: List<String> get() = commits.map { it.hash }
}

/**
 * Reads the commit selection from the Git Log tool window.
 *
 * The action entry point ([com.github.sandogeek.batchcherrypickplugin.action.BatchCherryPickAction])
 * is registered only on the Git Log context menu, so the [VcsLogDataKeys] are expected to be present.
 */
object VcsLogSelectionHelper {

    /**
     * Returns the selected commits, or `null` when the selection is unusable:
     * empty, spanning multiple repository roots, or belonging to a root without a Git repository.
     */
    fun getSelectedCommits(event: AnActionEvent): SelectedCommits? {
        val project = event.project ?: return null
        val selection = event.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)

        val commitIds: List<CommitId> = selection?.commits
            ?: event.getData(VcsLogDataKeys.VCS_LOG)?.selectedCommits
            ?: return null
        if (commitIds.isEmpty()) return null

        // All commits must share a single repository root.
        val roots = commitIds.map { it.root }.toSet()
        if (roots.size != 1) return null
        val root = roots.first()
        val repository = resolveRepository(project, root) ?: return null

        val metadataByHash = collectMetadata(selection)
        val commits = commitIds.map { id ->
            val hash = id.hash.asString()
            val meta = metadataByHash[hash]
            CommitInfo(
                hash = hash,
                subject = meta?.subject ?: "",
                author = meta?.author?.name ?: "",
                timestamp = meta?.authorTime ?: 0L,
            )
        }
        return SelectedCommits(repository, commits)
    }

    /** True when the current selection is valid for the action (single root, at least one commit). */
    fun hasValidSelection(event: AnActionEvent): Boolean = getSelectedCommits(event) != null

    private fun collectMetadata(selection: VcsLogCommitSelection?): Map<String, VcsCommitMetadata> {
        val metadata = selection?.cachedMetadata ?: return emptyMap()
        return metadata.associateBy { it.id.asString() }
    }

    private fun resolveRepository(project: Project, root: VirtualFile): GitRepository? =
        GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root)
}
