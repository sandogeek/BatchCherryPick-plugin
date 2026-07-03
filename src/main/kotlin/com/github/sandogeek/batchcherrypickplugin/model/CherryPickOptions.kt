package com.github.sandogeek.batchcherrypickplugin.model

/**
 * Immutable configuration for a batch cherry-pick run.
 *
 * The commits themselves come from the Git Log selection (see
 * [com.github.sandogeek.batchcherrypickplugin.util.VcsLogSelectionHelper]); this object only carries
 * the target branches and execution flags gathered from the dialog.
 */
data class CherryPickOptions(
    /** Full commit hashes selected in the Git Log, in selection order (sorted topologically later). */
    val selectedCommits: List<String>,
    /** Target branch names to cherry-pick onto. */
    val targetBranches: List<String>,
    /** Remote name used for pull/push (default `origin`). */
    val remote: String = "origin",
    /** Run `git pull` on each target branch before cherry-picking. */
    val pull: Boolean = false,
    /** Run `git push` after a branch finished without failures. */
    val push: Boolean = false,
    /** Allow running against a dirty worktree instead of blocking. */
    val allowDirty: Boolean = false,
    /** Preview the plan without touching the repository. */
    val dryRun: Boolean = false,
    /** Behaviour on merge conflicts. */
    val conflictStrategy: ConflictStrategy = ConflictStrategy.ABORT,
)
