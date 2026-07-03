package com.github.sandogeek.batchcherrypickplugin.model

import com.github.sandogeek.batchcherrypickplugin.BatchCherryPickBundle

/**
 * How to react when a cherry-pick stops due to a merge conflict.
 * Mirrors the `--on-conflict` option of the reference `cherry_pick_batch.py` script.
 */
enum class ConflictStrategy(private val displayKey: String) {
    /** Abort the current cherry-pick and stop processing the branch. */
    ABORT("conflict.abort"),

    /** Abort the current cherry-pick and continue with the next commit. */
    SKIP("conflict.skip"),

    /** Resolve the conflict by taking the incoming (cherry-picked) changes. */
    THEIRS("conflict.theirs"),

    /** Resolve the conflict by keeping the current branch changes. */
    OURS("conflict.ours");

    val displayName: String
        get() = BatchCherryPickBundle.message(displayKey)

    override fun toString(): String = displayName
}
