package com.github.sandogeek.batchcherrypickplugin.ui

import com.github.sandogeek.batchcherrypickplugin.BatchCherryPickBundle
import com.github.sandogeek.batchcherrypickplugin.model.CherryPickOptions
import com.github.sandogeek.batchcherrypickplugin.model.ConflictStrategy
import com.github.sandogeek.batchcherrypickplugin.util.CommitInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * Configuration dialog. Displays the selected commits read-only and lets the user pick target
 * branches and execution options. Commits are never entered here — they come from the Git Log.
 */
class BatchCherryPickDialog(
    project: Project,
    private val repository: GitRepository,
    private val commits: List<CommitInfo>,
) : DialogWrapper(project) {

    private val branchList = CheckBoxList<String>()
    private val branchFilter = JBTextField()
    private val showRemoteCheckBox = JCheckBox(BatchCherryPickBundle.message("dialog.showRemote"))
    private val remoteField = JBTextField("origin", 12)
    private val pullCheckBox = JCheckBox(BatchCherryPickBundle.message("dialog.pull"))
    private val pushCheckBox = JCheckBox(BatchCherryPickBundle.message("dialog.push"))
    private val allowDirtyCheckBox = JCheckBox(BatchCherryPickBundle.message("dialog.allowDirty"))
    private val dryRunCheckBox = JCheckBox(BatchCherryPickBundle.message("dialog.dryRun"))
    private val conflictCombo = com.intellij.openapi.ui.ComboBox(ConflictStrategy.values())

    private val currentBranchName = repository.currentBranch?.name

    init {
        title = BatchCherryPickBundle.message("dialog.title")
        conflictCombo.selectedItem = ConflictStrategy.ABORT
        populateBranches()
        showRemoteCheckBox.addActionListener { populateBranches() }
        branchFilter.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = populateBranches()
        })
        init()
    }

    private fun populateBranches() {
        val previouslyChecked = branchList.checkedItems.toSet()
        branchList.clear()
        val filter = branchFilter.text.trim().lowercase()

        val names = mutableListOf<String>()
        repository.branches.localBranches.forEach { names.add(it.name) }
        if (showRemoteCheckBox.isSelected) {
            repository.branches.remoteBranches.forEach { names.add(it.nameForLocalOperations) }
        }
        names.asSequence()
            .distinct()
            // Cherry-picking onto the branch you're already on is a no-op; hide it.
            .filter { it != currentBranchName }
            .filter { filter.isEmpty() || it.lowercase().contains(filter) }
            .sorted()
            .forEach { branchList.addItem(it, it, it in previouslyChecked) }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(8)))
        root.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(560))

        root.add(buildCommitPanel(), BorderLayout.NORTH)
        root.add(buildBranchPanel(), BorderLayout.CENTER)
        root.add(buildOptionsPanel(), BorderLayout.SOUTH)
        return root
    }

    private fun buildCommitPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(JBLabel(BatchCherryPickBundle.message("dialog.commits.header", commits.size)), BorderLayout.NORTH)

        val model = DefaultListModel<String>()
        commits.forEach { model.addElement(formatCommit(it)) }
        val list = JList(model)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 6
        val scroll = ScrollPaneFactory.createScrollPane(list)
        scroll.preferredSize = Dimension(JBUI.scale(540), JBUI.scale(130))
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun formatCommit(info: CommitInfo): String {
        val subject = info.subject.ifBlank { BatchCherryPickBundle.message("dialog.commits.noSubject") }
        val author = if (info.author.isNotBlank()) "  —  ${info.author}" else ""
        return "${info.shortHash}  $subject$author"
    }

    private fun buildBranchPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        val header = JPanel(BorderLayout(JBUI.scale(8), 0))
        header.add(JBLabel(BatchCherryPickBundle.message("dialog.branches.header")), BorderLayout.WEST)
        header.add(branchFilter, BorderLayout.CENTER)
        header.add(showRemoteCheckBox, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)
        panel.add(ScrollPaneFactory.createScrollPane(branchList), BorderLayout.CENTER)
        return panel
    }

    private fun buildOptionsPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        val remoteRow = JPanel(BorderLayout(JBUI.scale(8), 0))
        val remoteInner = JPanel()
        remoteInner.layout = BoxLayout(remoteInner, BoxLayout.X_AXIS)
        remoteInner.add(JBLabel(BatchCherryPickBundle.message("dialog.remote")))
        remoteInner.add(remoteField)
        remoteRow.add(remoteInner, BorderLayout.WEST)
        panel.add(remoteRow)

        val flags = JPanel()
        flags.layout = BoxLayout(flags, BoxLayout.X_AXIS)
        flags.add(pullCheckBox)
        flags.add(pushCheckBox)
        flags.add(allowDirtyCheckBox)
        flags.add(dryRunCheckBox)
        panel.add(flags)

        val conflictRow = JPanel()
        conflictRow.layout = BoxLayout(conflictRow, BoxLayout.X_AXIS)
        conflictRow.add(JBLabel(BatchCherryPickBundle.message("dialog.conflict")))
        conflictRow.add(conflictCombo)
        panel.add(conflictRow)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (branchList.checkedItems.isEmpty()) {
            return ValidationInfo(BatchCherryPickBundle.message("dialog.error.noBranch"), branchList)
        }
        if (remoteField.text.isBlank() && (pullCheckBox.isSelected || pushCheckBox.isSelected)) {
            return ValidationInfo(BatchCherryPickBundle.message("dialog.error.noRemote"), remoteField)
        }
        return null
    }

    fun buildOptions(): CherryPickOptions = CherryPickOptions(
        selectedCommits = commits.map { it.hash },
        targetBranches = branchList.checkedItems,
        remote = remoteField.text.trim().ifBlank { "origin" },
        pull = pullCheckBox.isSelected,
        push = pushCheckBox.isSelected,
        allowDirty = allowDirtyCheckBox.isSelected,
        dryRun = dryRunCheckBox.isSelected,
        conflictStrategy = conflictCombo.selectedItem as ConflictStrategy,
    )
}
