# Batch Cherry-Pick

![Build](https://github.com/sandogeek/BatchCherryPick-plugin/workflows/Build/badge.svg)

<!-- Plugin description -->
A JetBrains IDE plugin that cherry-picks one or more commits selected in the **Git Log** onto
**multiple target branches** in a single operation. Commits are applied in topological order, and
the branch you started on is restored when the run finishes.

Feature parity with the reference `cherry_pick_batch.py` script: multiple targets, pull/push,
allow-dirty, conflict strategy (abort / skip / theirs / ours), and dry-run.
<!-- Plugin description end -->

## Usage

1. Open the Git Log (**Git → Show Git Log**, or the Git tool window).
2. Select one or more commits (use <kbd>Ctrl</kbd>/<kbd>Shift</kbd> for multi-select).
3. Right-click → **Batch Cherry-Pick to Branches...**
4. In the dialog, check the target branches and configure the options:
   - **Remote** (default `origin`), **Pull**, **Push**
   - **Allow dirty worktree** — skip the clean-worktree guard
   - **Dry run** — preview the plan without changing anything
   - **On conflict** — Abort / Skip / Theirs / Ours
5. Click **OK**, confirm, and watch the background task. A result dialog and notification summarize
   what was applied, skipped, or failed per branch.

## Behavior notes

- Commits come **only** from the Git Log selection — there is no SHA/range text input.
- All selected commits must belong to a single repository root; cross-root selections disable the action.
- The current branch is hidden from the target list (cherry-picking onto it is a no-op).
- `theirs`/`ours` auto-resolution is best-effort; complex conflicts may still fail.
- A failed push does not roll back the local cherry-picks — it is flagged in the report.

## Building

- Build: `./gradlew build`
- Run in a sandbox IDE: `./gradlew runIde`
- Verify API compatibility: `./gradlew verifyPlugin`

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
