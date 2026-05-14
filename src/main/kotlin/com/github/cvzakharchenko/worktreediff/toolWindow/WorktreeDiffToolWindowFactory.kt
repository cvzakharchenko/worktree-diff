package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.diff.DiffOpener
import com.github.cvzakharchenko.worktreediff.git.ComparisonResult
import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.github.cvzakharchenko.worktreediff.git.GitCommandException
import com.github.cvzakharchenko.worktreediff.git.WorktreeComparisonService
import com.github.cvzakharchenko.worktreediff.git.WorktreeInfo
import com.github.cvzakharchenko.worktreediff.git.WorktreeService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class WorktreeDiffToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WorktreeDiffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private class WorktreeDiffPanel(
    private val project: Project,
    private val worktreeService: WorktreeService = WorktreeService(),
    private val comparisonService: WorktreeComparisonService = WorktreeComparisonService(),
    private val diffOpener: DiffOpener = DiffOpener(project),
) {
    private val worktreeModel = DefaultComboBoxModel<WorktreeInfo>()
    private val worktreeComboBox = ComboBox(worktreeModel)
    private val includeLocalChanges = JCheckBox("Include local changes")
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh"
    }
    private val statusLabel = JBLabel()
    private val fileListModel = DefaultListModel<FileComparison>()
    private val fileList = JBList(fileListModel)

    private var repositoryRoot: Path? = null
    private var suppressSelectionEvents = false
    private var refreshGeneration = 0

    val component: JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(createControls(), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(fileList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    init {
        configureList()
        worktreeComboBox.addActionListener {
            if (!suppressSelectionEvents) {
                refreshSelectedComparison()
            }
        }
        includeLocalChanges.addActionListener {
            refreshSelectedComparison()
        }
        refreshButton.addActionListener {
            refreshWorktreesAndComparison()
        }

        refreshWorktreesAndComparison()
    }

    private fun createControls(): JComponent {
        val top = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(4)))
        top.add(worktreeComboBox, BorderLayout.CENTER)
        top.add(refreshButton, BorderLayout.EAST)

        val options = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        options.add(includeLocalChanges)

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(top, BorderLayout.NORTH)
            add(options, BorderLayout.SOUTH)
        }
    }

    private fun configureList() {
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.emptyText.text = "No differences"
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) {
                    openSelectedDiff()
                }
            }
        })
        fileList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openDiff")
        fileList.actionMap.put("openDiff", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                openSelectedDiff()
            }
        })
    }

    private fun refreshWorktreesAndComparison() {
        val selectedPath = selectedWorktree()?.path
        val includeLocal = includeLocalChanges.isSelected
        val generation = nextGeneration()
        setBusy("Refreshing worktrees...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val root = project.basePath
                    ?.let { Path.of(it) }
                    ?.let { worktreeService.findRepositoryRoot(it) }
                    ?: return@runCatching RefreshState(message = "Project is not inside a Git repository.")

                val worktrees = worktreeService.listOtherWorktrees(root)
                val selected = worktrees.firstOrNull { it.path == selectedPath } ?: worktrees.firstOrNull()
                val comparison = selected?.let {
                    comparisonService.compare(root, it, includeLocal)
                } ?: ComparisonResult(emptyList())

                RefreshState(
                    repositoryRoot = root,
                    worktrees = worktrees,
                    selected = selected,
                    comparison = comparison,
                    message = when {
                        worktrees.isEmpty() -> "No other worktrees found."
                        comparison.entries.isEmpty() -> "No differences."
                        else -> "${comparison.entries.size} file(s)."
                    },
                )
            }.fold(
                onSuccess = { it },
                onFailure = { RefreshState(error = it.userMessage()) },
            )

            applyStateLater(generation, result)
        }
    }

    private fun refreshSelectedComparison() {
        val root = repositoryRoot ?: return refreshWorktreesAndComparison()
        val selected = selectedWorktree() ?: return clearFiles("No other worktrees found.")
        val includeLocal = includeLocalChanges.isSelected
        val generation = nextGeneration()
        setBusy("Refreshing comparison...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val comparison = comparisonService.compare(root, selected, includeLocal)
                RefreshState(
                    repositoryRoot = root,
                    worktrees = currentWorktrees(),
                    selected = selected,
                    comparison = comparison,
                    message = if (comparison.entries.isEmpty()) {
                        "No differences."
                    } else {
                        "${comparison.entries.size} file(s)."
                    },
                )
            }.fold(
                onSuccess = { it },
                onFailure = { RefreshState(error = it.userMessage()) },
            )

            applyStateLater(generation, result)
        }
    }

    private fun applyStateLater(generation: Int, state: RefreshState) {
        SwingUtilities.invokeLater {
            if (project.isDisposed || generation != refreshGeneration) {
                return@invokeLater
            }
            applyState(state)
        }
    }

    private fun applyState(state: RefreshState) {
        if (state.error == null) {
            repositoryRoot = state.repositoryRoot
            updateWorktreeModel(state.worktrees, state.selected)

            val entries = state.comparison?.entries.orEmpty()
            fileListModel.clear()
            entries.forEach(fileListModel::addElement)
        }

        val message = state.error ?: state.message.orEmpty()
        statusLabel.text = message
        setControlsEnabled(enabled = true)
    }

    private fun updateWorktreeModel(worktrees: List<WorktreeInfo>, selected: WorktreeInfo?) {
        suppressSelectionEvents = true
        try {
            worktreeModel.removeAllElements()
            worktrees.forEach(worktreeModel::addElement)
            if (selected != null) {
                worktreeComboBox.selectedItem = selected
            }
        } finally {
            suppressSelectionEvents = false
        }
    }

    private fun selectedWorktree(): WorktreeInfo? =
        worktreeComboBox.selectedItem as? WorktreeInfo

    private fun currentWorktrees(): List<WorktreeInfo> =
        (0 until worktreeModel.size).map { worktreeModel.getElementAt(it) }

    private fun clearFiles(message: String) {
        fileListModel.clear()
        statusLabel.text = message
    }

    private fun setBusy(message: String) {
        statusLabel.text = message
        setControlsEnabled(false)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        refreshButton.isEnabled = enabled
        worktreeComboBox.isEnabled = enabled && worktreeModel.size > 0
        includeLocalChanges.isEnabled = enabled && worktreeModel.size > 0
        fileList.isEnabled = enabled
    }

    private fun nextGeneration(): Int {
        refreshGeneration += 1
        return refreshGeneration
    }

    private fun openSelectedDiff() {
        val index = fileList.selectedIndex
        if (index !in 0 until fileListModel.size) {
            return
        }
        val entries = (0 until fileListModel.size).map { fileListModel.getElementAt(it) }
        diffOpener.open(entries, index)
    }
}

private data class RefreshState(
    val repositoryRoot: Path? = null,
    val worktrees: List<WorktreeInfo> = emptyList(),
    val selected: WorktreeInfo? = null,
    val comparison: ComparisonResult? = null,
    val message: String? = null,
    val error: String? = null,
)

private fun Throwable.userMessage(): String =
    when (this) {
        is GitCommandException -> message ?: "Git command failed."
        else -> message ?: "Refresh failed."
    }
