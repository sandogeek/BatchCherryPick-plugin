package com.github.sandogeek.batchcherrypickplugin.ui

import com.github.sandogeek.batchcherrypickplugin.BatchCherryPickBundle
import com.github.sandogeek.batchcherrypickplugin.model.BatchCherryPickReport
import com.github.sandogeek.batchcherrypickplugin.model.BranchCherryPickResult
import com.github.sandogeek.batchcherrypickplugin.model.CommitResult
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.ScrollPaneConstants

/** Read-only summary of a batch cherry-pick run, grouped by target branch. */
class CherryPickResultDialog(
    project: Project,
    private val report: BatchCherryPickReport,
) : DialogWrapper(project) {

    init {
        title = if (report.dryRun) {
            BatchCherryPickBundle.message("result.title.dryRun")
        } else {
            BatchCherryPickBundle.message("result.title")
        }
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val pane = JEditorPane("text/html", buildHtml())
        pane.isEditable = false
        pane.background = null
        val scroll = ScrollPaneFactory.createScrollPane(pane)
        scroll.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(460))
        return scroll
    }

    private fun buildHtml(): String {
        val sb = StringBuilder("<html><body style='font-family:sans-serif;'>")
        sb.append("<h3>")
            .append(BatchCherryPickBundle.message("result.summary",
                report.totalApplied, report.totalSkipped, report.totalFailed))
            .append("</h3>")

        for (branch in report.branchResults) {
            sb.append(renderBranch(branch))
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun renderBranch(branch: BranchCherryPickResult): String {
        val sb = StringBuilder()
        sb.append("<h4>").append(escape(branch.branch)).append("</h4>")

        branch.fatalError?.let {
            sb.append("<p style='color:#c0392b;'>").append(escape(it)).append("</p>")
            return sb.toString()
        }

        branch.messages.forEach { sb.append("<div style='color:#7f8c8d;'>").append(escape(it)).append("</div>") }

        sb.append("<ul>")
        renderGroup(sb, "result.applied", branch.applied, "#27ae60")
        renderGroup(sb, "result.skipped", branch.skipped, "#f39c12")
        renderGroup(sb, "result.failed", branch.failed, "#c0392b")
        sb.append("</ul>")
        return sb.toString()
    }

    private fun renderGroup(sb: StringBuilder, labelKey: String, items: List<CommitResult>, color: String) {
        if (items.isEmpty()) return
        sb.append("<li><b style='color:").append(color).append(";'>")
            .append(BatchCherryPickBundle.message(labelKey, items.size)).append("</b><ul>")
        for (item in items) {
            sb.append("<li>").append(escape(item.shortHash)).append(" ").append(escape(item.subject))
            item.detail?.let { sb.append(" <i style='color:#95a5a6;'>(").append(escape(it)).append(")</i>") }
            sb.append("</li>")
        }
        sb.append("</ul></li>")
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
