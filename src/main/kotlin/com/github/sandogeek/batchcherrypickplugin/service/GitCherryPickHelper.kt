package com.github.sandogeek.batchcherrypickplugin.service

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository

/**
 * Thin wrapper over the git4idea command API for the operations the batch cherry-pick flow needs.
 *
 * All methods run synchronously and are meant to be called from a background task.
 */
class GitCherryPickHelper(
    private val project: Project,
    private val repository: GitRepository,
) {
    private val git: Git = Git.getInstance()
    private val root = repository.root

    private fun run(command: GitCommand, vararg params: String): GitCommandResult {
        val handler = GitLineHandler(project, root, command)
        handler.addParameters(*params)
        return git.runCommand(handler)
    }

    /**
     * Orders the given commit hashes topologically (parents before children), matching the
     * reference script's `git rev-list --no-walk --topo-order --reverse`.
     * Falls back to the input order when rev-list fails.
     */
    fun sortTopologically(hashes: List<String>): List<String> {
        if (hashes.size <= 1) return hashes
        val handler = GitLineHandler(project, root, GitCommand.REV_LIST)
        handler.addParameters("--no-walk", "--topo-order", "--reverse")
        handler.addParameters(*hashes.toTypedArray())
        val result = git.runCommand(handler)
        if (!result.success()) return hashes
        val ordered = result.output.map { it.trim() }.filter { it.isNotEmpty() }
        // Map back to the originally-selected hash strings (rev-list may print full 40-char hashes).
        val bySelected = hashes.associateBy { sel -> ordered.firstOrNull { it.startsWith(sel) || sel.startsWith(it) } }
        return ordered.mapNotNull { full -> bySelected[full] ?: hashes.firstOrNull { full.startsWith(it) || it.startsWith(full) } }
            .ifEmpty { hashes }
    }

    /** Returns true when the worktree is clean (no staged or unstaged changes). */
    fun isClean(): Boolean {
        val result = run(GitCommand.STATUS, "--porcelain")
        return result.success() && result.output.none { it.isNotBlank() }
    }

    fun currentBranchName(): String? = repository.currentBranch?.name

    /**
     * Reads the current branch directly from git, bypassing the possibly-stale cached repository
     * state. Raw git commands run through [GitLineHandler] do not refresh [GitRepository], so
     * [currentBranchName] can report the branch we started on even after several checkouts.
     * Returns null on a detached HEAD or when the command fails.
     */
    fun currentBranchFromGit(): String? {
        val result = run(GitCommand.REV_PARSE, "--abbrev-ref", "HEAD")
        if (!result.success()) return null
        return result.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "HEAD" }
    }

    /** Refreshes IntelliJ's cached repository state so the IDE reflects branch changes made via raw git. */
    fun refreshRepositoryState() = repository.update()

    fun checkout(branch: String): GitCommandResult = run(GitCommand.CHECKOUT, branch)

    fun fetch(remote: String): GitCommandResult = run(GitCommand.FETCH, remote)

    fun pull(remote: String, branch: String): GitCommandResult =
        run(GitCommand.PULL, remote, branch, "--ff-only")

    fun push(remote: String, branch: String): GitCommandResult =
        run(GitCommand.PUSH, remote, branch)

    /** Applies a single commit. Does not commit automatically on conflict; caller inspects the result. */
    fun cherryPick(hash: String): GitCommandResult = run(GitCommand.CHERRY_PICK, hash)

    fun cherryPickAbort(): GitCommandResult = run(GitCommand.CHERRY_PICK, "--abort")

    fun cherryPickContinue(): GitCommandResult {
        val handler = GitLineHandler(project, root, GitCommand.CHERRY_PICK)
        handler.addParameters("--continue")
        // Avoid opening an editor for the commit message.
        handler.addParameters("--no-edit")
        return git.runCommand(handler)
    }

    /**
     * Resolves conflicts by checking out every conflicted path from one side, then staging them.
     * `theirs` = the commit being cherry-picked, `ours` = the branch being applied onto.
     */
    fun resolveConflictsFrom(theirs: Boolean): GitCommandResult {
        val conflicted = conflictedPaths()
        if (conflicted.isEmpty()) return run(GitCommand.STATUS)
        val sideFlag = if (theirs) "--theirs" else "--ours"
        val checkout = GitLineHandler(project, root, GitCommand.CHECKOUT)
        checkout.addParameters(sideFlag, "--")
        checkout.addParameters(*conflicted.toTypedArray())
        val checkoutResult = git.runCommand(checkout)
        if (!checkoutResult.success()) return checkoutResult

        val add = GitLineHandler(project, root, GitCommand.ADD)
        add.addParameters("--")
        add.addParameters(*conflicted.toTypedArray())
        return git.runCommand(add)
    }

    /** Paths currently in a conflicted (unmerged) state. */
    private fun conflictedPaths(): List<String> {
        val result = run(GitCommand.STATUS, "--porcelain")
        if (!result.success()) return emptyList()
        // Unmerged entries are marked with combinations like UU, AA, DD, AU, UA, DU, UD.
        val unmergedCodes = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")
        return result.output.mapNotNull { line ->
            if (line.length < 3) return@mapNotNull null
            val code = line.substring(0, 2)
            if (code in unmergedCodes) line.substring(3).trim() else null
        }
    }
}
