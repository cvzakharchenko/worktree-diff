package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.diff.DiffOpener
import com.github.cvzakharchenko.worktreediff.git.ComparisonResult
import com.github.cvzakharchenko.worktreediff.git.GitCommandException
import com.github.cvzakharchenko.worktreediff.git.WorktreeComparisonService
import com.github.cvzakharchenko.worktreediff.git.WorktreeInfo
import com.github.cvzakharchenko.worktreediff.git.WorktreeService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class WorktreeDiffToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WorktreeDiffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(panel.titleActions())
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
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh"
    }
    private val statusLabel = JBLabel()
    private val fileTree = ComparisonTreePanel(project, ::openSelectedDiff)

    private var includeLocalChanges = false
    private var ignoreLineEndings = false
    private var optionsEnabled = false

    private val includeLocalChangesAction = BooleanOptionAction(
        text = "Include Local Changes",
        description = "Include files changed locally on either side even when their disk content matches.",
        isEnabled = { optionsEnabled },
        isSelected = { includeLocalChanges },
        setSelected = {
            includeLocalChanges = it
            refreshSelectedComparison()
        },
    )
    private val ignoreLineEndingsAction = BooleanOptionAction(
        text = "Ignore Line Endings",
        description = "Ignore CRLF, CR, and LF differences when filtering unchanged files.",
        isEnabled = { optionsEnabled },
        isSelected = { ignoreLineEndings },
        setSelected = {
            ignoreLineEndings = it
            refreshSelectedComparison()
        },
    )
    private val optionsAction = DefaultActionGroup("Options", "Comparison options", AllIcons.General.GearPlain).apply {
        setPopup(true)
        add(includeLocalChangesAction)
        add(ignoreLineEndingsAction)
    }

    private var repositoryRoot: Path? = null
    private var suppressSelectionEvents = false
    private var refreshGeneration = 0

    val component: JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(createControls(), BorderLayout.NORTH)
        add(fileTree.component, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    init {
        worktreeComboBox.addActionListener {
            if (!suppressSelectionEvents) {
                refreshSelectedComparison()
            }
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

        return top
    }

    fun titleActions(): List<AnAction> =
        listOf(optionsAction) + fileTree.titleActions()

    private fun refreshWorktreesAndComparison() {
        val selectedPath = selectedWorktree()?.path
        val includeLocal = includeLocalChanges
        val ignoreEol = ignoreLineEndings
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
                    comparisonService.compare(root, it, includeLocal, ignoreEol)
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
        val includeLocal = includeLocalChanges
        val ignoreEol = ignoreLineEndings
        val generation = nextGeneration()
        setBusy("Refreshing comparison...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val comparison = comparisonService.compare(root, selected, includeLocal, ignoreEol)
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

            fileTree.setEntries(state.comparison?.entries.orEmpty())
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
        fileTree.clear()
        statusLabel.text = message
    }

    private fun setBusy(message: String) {
        statusLabel.text = message
        setControlsEnabled(false)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        refreshButton.isEnabled = enabled
        worktreeComboBox.isEnabled = enabled && worktreeModel.size > 0
        optionsEnabled = enabled && worktreeModel.size > 0
        fileTree.setEnabled(enabled)
    }

    private fun nextGeneration(): Int {
        refreshGeneration += 1
        return refreshGeneration
    }

    private fun openSelectedDiff() {
        val index = fileTree.selectedVisibleIndex()
        val entries = fileTree.visibleEntries()
        if (index !in entries.indices) {
            return
        }
        diffOpener.open(entries, index) { relativePath ->
            SwingUtilities.invokeLater {
                fileTree.selectEntry(relativePath)
            }
        }
    }
}

private class BooleanOptionAction(
    text: String,
    description: String,
    private val isEnabled: () -> Boolean,
    private val isSelected: () -> Boolean,
    private val setSelected: (Boolean) -> Unit,
) : DumbAwareToggleAction(text, description, null as Icon?) {

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.isEnabled = isEnabled()
    }

    override fun isSelected(event: AnActionEvent): Boolean =
        isSelected()

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        setSelected(state)
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
