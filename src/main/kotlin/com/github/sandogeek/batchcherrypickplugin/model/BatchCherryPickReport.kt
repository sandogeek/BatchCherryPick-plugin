package com.github.sandogeek.batchcherrypickplugin.model

/** Outcome of a single commit applied onto a single branch. */
enum class CommitOutcome { APPLIED, SKIPPED, FAILED }

/** Result of cherry-picking one commit, kept for the per-branch report. */
data class CommitResult(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val outcome: CommitOutcome,
    val detail: String? = null,
)

/** Result of processing one target branch. */
data class BranchCherryPickResult(
    val branch: String,
    val applied: MutableList<CommitResult> = mutableListOf(),
    val skipped: MutableList<CommitResult> = mutableListOf(),
    val failed: MutableList<CommitResult> = mutableListOf(),
    /** Non-fatal messages such as checkout/pull/push notes. */
    val messages: MutableList<String> = mutableListOf(),
    /** Set when the branch could not be processed at all (e.g. checkout failed). */
    var fatalError: String? = null,
    /** Whether the push step (if requested) succeeded. */
    var pushed: Boolean = false,
) {
    fun record(result: CommitResult) {
        when (result.outcome) {
            CommitOutcome.APPLIED -> applied
            CommitOutcome.SKIPPED -> skipped
            CommitOutcome.FAILED -> failed
        }.add(result)
    }

    val hasFailures: Boolean
        get() = fatalError != null || failed.isNotEmpty()
}

/** Aggregate report over all target branches. */
data class BatchCherryPickReport(
    val dryRun: Boolean,
    val branchResults: List<BranchCherryPickResult>,
) {
    val hasFailures: Boolean
        get() = branchResults.any { it.hasFailures }

    val totalApplied: Int get() = branchResults.sumOf { it.applied.size }
    val totalSkipped: Int get() = branchResults.sumOf { it.skipped.size }
    val totalFailed: Int get() = branchResults.sumOf { it.failed.size }
}
