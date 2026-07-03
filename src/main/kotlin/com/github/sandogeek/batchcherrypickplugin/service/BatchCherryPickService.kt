package com.github.sandogeek.batchcherrypickplugin.service

import com.github.sandogeek.batchcherrypickplugin.BatchCherryPickBundle
import com.github.sandogeek.batchcherrypickplugin.model.BatchCherryPickReport
import com.github.sandogeek.batchcherrypickplugin.model.BranchCherryPickResult
import com.github.sandogeek.batchcherrypickplugin.model.CherryPickOptions
import com.github.sandogeek.batchcherrypickplugin.model.CommitOutcome
import com.github.sandogeek.batchcherrypickplugin.model.CommitResult
import com.github.sandogeek.batchcherrypickplugin.model.ConflictStrategy
import com.github.sandogeek.batchcherrypickplugin.util.CommitInfo
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommandResult
import git4idea.repo.GitRepository

/**
 * Core batch cherry-pick flow, aligned with the reference `cherry_pick_batch.py` execution stage.
 *
 * For each target branch: checkout -> optional pull -> apply each commit in topological order,
 * handling conflicts per the configured strategy -> optional push. The original branch is always
 * restored at the end.
 */
class BatchCherryPickService(
    private val project: Project,
    private val repository: GitRepository,
) {
    private val helper = GitCherryPickHelper(project, repository)

    /**
     * Executes the batch cherry-pick.
     *
     * @param commitInfos selected commits, used for hash resolution and human-readable reporting
     * @param options     target branches and execution flags
     * @param indicator   progress indicator; cancellation is honoured between commits
     */
    fun execute(
        commitInfos: List<CommitInfo>,
        options: CherryPickOptions,
        indicator: ProgressIndicator,
    ): BatchCherryPickReport {
        val infoByHash = commitInfos.associateBy { it.hash }
        val orderedHashes = helper.sortTopologically(options.selectedCommits)
        val originalBranch = helper.currentBranchName()

        val branchResults = mutableListOf<BranchCherryPickResult>()
        try {
            // Guard against a dirty worktree unless the user explicitly allowed it.
            if (!options.allowDirty && !options.dryRun && !helper.isClean()) {
                return dirtyWorktreeReport(options)
            }

            val totalWork = (options.targetBranches.size * orderedHashes.size).coerceAtLeast(1)
            var doneWork = 0
            for (branch in options.targetBranches) {
                indicator.checkCanceled()
                val result = BranchCherryPickResult(branch)
                branchResults.add(result)

                if (options.dryRun) {
                    fillDryRunPlan(result, orderedHashes, infoByHash, options)
                    doneWork += orderedHashes.size
                    indicator.fraction = doneWork.toDouble() / totalWork
                    continue
                }

                processBranch(branch, orderedHashes, infoByHash, options, result, indicator) {
                    doneWork++
                    indicator.fraction = doneWork.toDouble() / totalWork
                }
            }
        } finally {
            // Always try to restore the branch the user started on. Compare against the real HEAD
            // read from git rather than the cached repository state, which stays stale because our
            // raw checkouts never refresh GitRepository.
            if (!options.dryRun && originalBranch != null && originalBranch != helper.currentBranchFromGit()) {
                val restore = helper.checkout(originalBranch)
                if (!restore.success()) {
                    thisLogger().warn("Failed to restore original branch $originalBranch: ${restore.errorOutputAsJoinedString}")
                }
            }
            // Refresh the IDE's cached branch/state so it reflects wherever HEAD actually ended up.
            if (!options.dryRun) {
                helper.refreshRepositoryState()
            }
        }
        return BatchCherryPickReport(options.dryRun, branchResults)
    }

    private fun processBranch(
        branch: String,
        orderedHashes: List<String>,
        infoByHash: Map<String, CommitInfo>,
        options: CherryPickOptions,
        result: BranchCherryPickResult,
        indicator: ProgressIndicator,
        onCommitProcessed: () -> Unit,
    ) {
        indicator.text = BatchCherryPickBundle.message("progress.branch", branch)

        val checkout = helper.checkout(branch)
        if (!checkout.success()) {
            result.fatalError = BatchCherryPickBundle.message("error.checkout", branch, checkout.errorOutputAsJoinedString)
            return
        }

        if (options.pull) {
            helper.fetch(options.remote)
            val pull = helper.pull(options.remote, branch)
            if (pull.success()) {
                result.messages.add(BatchCherryPickBundle.message("info.pulled", options.remote, branch))
            } else {
                result.messages.add(BatchCherryPickBundle.message("warn.pull.failed", branch, pull.errorOutputAsJoinedString))
            }
        }

        var stopBranch = false
        for (hash in orderedHashes) {
            indicator.checkCanceled()
            if (stopBranch) {
                recordSkippedRemaining(result, hash, infoByHash, BatchCherryPickBundle.message("info.aborted.remaining"))
                onCommitProcessed()
                continue
            }
            val stop = applyCommit(hash, infoByHash, options.conflictStrategy, result)
            stopBranch = stop
            onCommitProcessed()
        }

        if (options.push && !result.hasFailures) {
            val push = helper.push(options.remote, branch)
            if (push.success()) {
                result.pushed = true
                result.messages.add(BatchCherryPickBundle.message("info.pushed", options.remote, branch))
            } else {
                result.messages.add(BatchCherryPickBundle.message("warn.push.failed", branch, push.errorOutputAsJoinedString))
            }
        }
    }

    /**
     * Applies a single commit. Returns true when the branch should stop processing further commits
     * (i.e. an unresolved ABORT-strategy conflict or a hard failure).
     */
    private fun applyCommit(
        hash: String,
        infoByHash: Map<String, CommitInfo>,
        strategy: ConflictStrategy,
        result: BranchCherryPickResult,
    ): Boolean {
        val info = infoByHash[hash]
        val pick = helper.cherryPick(hash)
        if (pick.success()) {
            result.record(commitResult(hash, info, CommitOutcome.APPLIED))
            return false
        }

        // A non-success result on cherry-pick is treated as a conflict; resolve per strategy.
        return when (strategy) {
            ConflictStrategy.ABORT -> {
                helper.cherryPickAbort()
                result.record(commitResult(hash, info, CommitOutcome.FAILED,
                    BatchCherryPickBundle.message("conflict.detail.abort")))
                true
            }

            ConflictStrategy.SKIP -> {
                helper.cherryPickAbort()
                result.record(commitResult(hash, info, CommitOutcome.SKIPPED,
                    BatchCherryPickBundle.message("conflict.detail.skip")))
                false
            }

            ConflictStrategy.THEIRS, ConflictStrategy.OURS -> {
                resolveAndContinue(hash, info, strategy == ConflictStrategy.THEIRS, result)
            }
        }
    }

    private fun resolveAndContinue(
        hash: String,
        info: CommitInfo?,
        theirs: Boolean,
        result: BranchCherryPickResult,
    ): Boolean {
        val resolve = helper.resolveConflictsFrom(theirs)
        if (!resolve.success()) {
            helper.cherryPickAbort()
            result.record(commitResult(hash, info, CommitOutcome.FAILED,
                BatchCherryPickBundle.message("conflict.detail.resolveFailed", resolve.errorOutputAsJoinedString)))
            return true
        }
        val cont: GitCommandResult = helper.cherryPickContinue()
        return if (cont.success()) {
            val detailKey = if (theirs) "conflict.detail.theirs" else "conflict.detail.ours"
            result.record(commitResult(hash, info, CommitOutcome.APPLIED, BatchCherryPickBundle.message(detailKey)))
            false
        } else {
            helper.cherryPickAbort()
            result.record(commitResult(hash, info, CommitOutcome.FAILED,
                BatchCherryPickBundle.message("conflict.detail.continueFailed", cont.errorOutputAsJoinedString)))
            true
        }
    }

    private fun recordSkippedRemaining(
        result: BranchCherryPickResult,
        hash: String,
        infoByHash: Map<String, CommitInfo>,
        detail: String,
    ) {
        result.record(commitResult(hash, infoByHash[hash], CommitOutcome.SKIPPED, detail))
    }

    private fun fillDryRunPlan(
        result: BranchCherryPickResult,
        orderedHashes: List<String>,
        infoByHash: Map<String, CommitInfo>,
        options: CherryPickOptions,
    ) {
        result.messages.add(BatchCherryPickBundle.message("dryrun.plan", result.branch, orderedHashes.size))
        if (options.pull) result.messages.add(BatchCherryPickBundle.message("dryrun.pull", options.remote))
        if (options.push) result.messages.add(BatchCherryPickBundle.message("dryrun.push", options.remote))
        for (hash in orderedHashes) {
            result.record(commitResult(hash, infoByHash[hash], CommitOutcome.APPLIED,
                BatchCherryPickBundle.message("dryrun.commit")))
        }
    }

    private fun dirtyWorktreeReport(options: CherryPickOptions): BatchCherryPickReport {
        val results = options.targetBranches.map { branch ->
            BranchCherryPickResult(branch).apply {
                fatalError = BatchCherryPickBundle.message("error.dirty")
            }
        }
        return BatchCherryPickReport(options.dryRun, results)
    }

    private fun commitResult(
        hash: String,
        info: CommitInfo?,
        outcome: CommitOutcome,
        detail: String? = null,
    ): CommitResult {
        val shortHash = info?.shortHash ?: if (hash.length > 8) hash.substring(0, 8) else hash
        return CommitResult(hash, shortHash, info?.subject ?: "", outcome, detail)
    }
}
