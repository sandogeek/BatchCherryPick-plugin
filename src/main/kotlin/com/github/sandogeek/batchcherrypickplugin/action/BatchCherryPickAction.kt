package com.github.sandogeek.batchcherrypickplugin.action

import com.github.sandogeek.batchcherrypickplugin.BatchCherryPickBundle
import com.github.sandogeek.batchcherrypickplugin.model.BatchCherryPickReport
import com.github.sandogeek.batchcherrypickplugin.model.CherryPickOptions
import com.github.sandogeek.batchcherrypickplugin.service.BatchCherryPickService
import com.github.sandogeek.batchcherrypickplugin.ui.BatchCherryPickDialog
import com.github.sandogeek.batchcherrypickplugin.ui.CherryPickResultDialog
import com.github.sandogeek.batchcherrypickplugin.util.CommitInfo
import com.github.sandogeek.batchcherrypickplugin.util.SelectedCommits
import com.github.sandogeek.batchcherrypickplugin.util.VcsLogSelectionHelper
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.repo.GitRepository

/**
 * Git Log context-menu entry point. Reads the selected commits, opens the configuration dialog,
 * and runs [BatchCherryPickService] on a background task.
 */
class BatchCherryPickAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = BatchCherryPickBundle.message("action.text")
        e.presentation.description = BatchCherryPickBundle.message("action.description")
        e.presentation.isEnabledAndVisible = VcsLogSelectionHelper.hasValidSelection(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selection = VcsLogSelectionHelper.getSelectedCommits(e)
        if (selection == null) {
            Messages.showWarningDialog(
                project,
                BatchCherryPickBundle.message("error.selection"),
                BatchCherryPickBundle.message("action.text"),
            )
            return
        }

        val dialog = BatchCherryPickDialog(project, selection.repository, selection.commits)
        if (!dialog.showAndGet()) return

        val options = dialog.buildOptions()
        if (!options.dryRun && !confirm(project, options)) return

        runBatch(project, selection, options)
    }

    private fun confirm(project: Project, options: CherryPickOptions): Boolean {
        val message = BatchCherryPickBundle.message(
            "confirm.message",
            options.selectedCommits.size,
            options.targetBranches.size,
            options.targetBranches.joinToString(", "),
        )
        return Messages.showYesNoDialog(
            project,
            message,
            BatchCherryPickBundle.message("confirm.title"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
    }

    private fun runBatch(project: Project, selection: SelectedCommits, options: CherryPickOptions) {
        val repository = selection.repository
        val commits = selection.commits
        val title = if (options.dryRun) {
            BatchCherryPickBundle.message("progress.title.dryRun")
        } else {
            BatchCherryPickBundle.message("progress.title")
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var report: BatchCherryPickReport? = null

            override fun run(indicator: ProgressIndicator) {
                report = runService(project, repository, commits, options, indicator)
            }

            override fun onSuccess() {
                report?.let { showReport(project, it) }
            }
        })
    }

    private fun runService(
        project: Project,
        repository: GitRepository,
        commits: List<CommitInfo>,
        options: CherryPickOptions,
        indicator: ProgressIndicator,
    ): BatchCherryPickReport =
        BatchCherryPickService(project, repository).execute(commits, options, indicator)

    private fun showReport(project: Project, report: BatchCherryPickReport) {
        ApplicationManager.getApplication().invokeLater {
            notify(project, report)
            CherryPickResultDialog(project, report).show()
        }
    }

    private fun notify(project: Project, report: BatchCherryPickReport) {
        val type = if (report.hasFailures) NotificationType.WARNING else NotificationType.INFORMATION
        val content = BatchCherryPickBundle.message(
            "notify.summary",
            report.totalApplied,
            report.totalSkipped,
            report.totalFailed,
        )
        NotificationGroupManager.getInstance()
            .getNotificationGroup("BatchCherryPick.Notifications")
            .createNotification(BatchCherryPickBundle.message("notify.title"), content, type)
            .notify(project)
    }
}
