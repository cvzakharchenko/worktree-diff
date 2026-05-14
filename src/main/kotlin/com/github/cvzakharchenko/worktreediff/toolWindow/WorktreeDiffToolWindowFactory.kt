package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.diff.DiffOpener
import com.github.cvzakharchenko.worktreediff.git.ComparisonResult
import com.github.cvzakharchenko.worktreediff.git.GitCommandException
import com.github.cvzakharchenko.worktreediff.git.WorktreeComparisonService
import com.github.cvzakharchenko.worktreediff.git.WorktreeInfo
import com.github.cvzakharchenko.worktreediff.git.WorktreeService
import com.github.cvzakharchenko.worktreediff.settings.WorktreeDiffSettings
import com.github.cvzakharchenko.worktreediff.telemetry.RefreshTelemetry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

private val LOG = Logger.getInstance(WorktreeDiffToolWindowFactory::class.java)

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
    private val fileTree = ComparisonTreePanel(project, ::openSelectedDiff)
    private val worktreeModel = DefaultComboBoxModel<WorktreeInfo>()
    private val worktreeComboBox = ComboBox(worktreeModel).apply {
        toolTipText = "Select worktree to compare"
    }
    private val settings = project.service<WorktreeDiffSettings>()

    private var includeLocalChanges = settings.includeLocalChanges
    private var ignoreLineEndings = settings.ignoreLineEndings
    private var refreshEnabled = false
    private var optionsEnabled = false
    private var repositoryRoot: Path? = null
    private var refreshGeneration = 0
    private var suppressSelectionEvents = false

    private val includeLocalChangesAction = BooleanOptionAction(
        text = "Include Local Changes",
        description = "Include files changed locally on either side even when their disk content matches.",
        isEnabled = { optionsEnabled },
        isSelected = { includeLocalChanges },
        setSelected = {
            includeLocalChanges = it
            settings.includeLocalChanges = it
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
            settings.ignoreLineEndings = it
            refreshSelectedComparison()
        },
    )
    private val optionsAction = DefaultActionGroup("Options", "Comparison options", AllIcons.General.GearPlain).apply {
        setPopup(true)
        add(includeLocalChangesAction)
        add(ignoreLineEndingsAction)
    }
    private val refreshAction = object : DumbAwareAction(
        "Refresh",
        "Refresh worktrees and comparison",
        AllIcons.Actions.Refresh,
    ) {
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = refreshEnabled
        }

        override fun actionPerformed(event: AnActionEvent) {
            val telemetry = RefreshTelemetry("refreshButton")
            telemetry.measure("saveAllDocuments") {
                FileDocumentManager.getInstance().saveAllDocuments()
            }
            refreshWorktreesAndComparison(telemetry)
        }
    }
    private val refreshToolbar = createRefreshToolbar()

    val component: JComponent = SimpleToolWindowPanel(true, true).apply {
        refreshToolbar.targetComponent = this
        setToolbar(createControlsToolbar())
        setContent(fileTree.component)
    }

    init {
        worktreeComboBox.addActionListener {
            if (!suppressSelectionEvents) {
                refreshSelectedComparison()
            }
        }
        refreshWorktreesAndComparison()
    }

    fun titleActions(): List<AnAction> =
        listOf(optionsAction) + fileTree.titleActions()

    private fun refreshWorktreesAndComparison(
        telemetry: RefreshTelemetry = RefreshTelemetry("refreshWorktrees"),
    ) {
        val selectedPath = selectedWorktree()?.path
        val includeLocal = includeLocalChanges
        val ignoreEol = ignoreLineEndings
        val generation = nextGeneration()
        telemetry.metric("includeLocalChanges", includeLocal)
        telemetry.metric("ignoreLineEndings", ignoreEol)
        setBusy("Refreshing worktrees...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val root = telemetry.measure("findRepositoryRoot") {
                    project.basePath
                        ?.let { Path.of(it) }
                        ?.let { worktreeService.findRepositoryRoot(it) }
                }
                    ?: return@runCatching RefreshState(emptyText = "Project is not inside a Git repository.")

                val worktrees = telemetry.measure("listWorktrees") {
                    worktreeService.listOtherWorktrees(root)
                }
                telemetry.metric("worktrees", worktrees.size)
                val selected = telemetry.measure("selectWorktree") {
                    worktrees.firstOrNull { it.path == selectedPath } ?: worktrees.firstOrNull()
                }
                val comparison = selected?.let {
                    telemetry.measure("compareTotal") {
                        comparisonService.compare(root, it, includeLocal, ignoreEol, telemetry)
                    }
                } ?: ComparisonResult(emptyList())

                RefreshState(
                    repositoryRoot = root,
                    worktrees = worktrees,
                    selected = selected,
                    comparison = comparison,
                    emptyText = if (worktrees.isEmpty()) "No other worktrees found." else "No differences",
                )
            }.fold(
                onSuccess = { it },
                onFailure = { RefreshState(error = it.userMessage()) },
            )

            applyStateLater(generation, result.copy(telemetry = telemetry))
        }
    }

    private fun refreshSelectedComparison() {
        val root = repositoryRoot ?: return refreshWorktreesAndComparison()
        val selected = selectedWorktree() ?: return clearFiles("No other worktrees found.")
        val includeLocal = includeLocalChanges
        val ignoreEol = ignoreLineEndings
        val generation = nextGeneration()
        val telemetry = RefreshTelemetry("refreshSelectedComparison")
        telemetry.metric("includeLocalChanges", includeLocal)
        telemetry.metric("ignoreLineEndings", ignoreEol)
        setBusy("Refreshing comparison...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val comparison = telemetry.measure("compareTotal") {
                    comparisonService.compare(root, selected, includeLocal, ignoreEol, telemetry)
                }
                RefreshState(
                    repositoryRoot = root,
                    worktrees = currentWorktrees(),
                    selected = selected,
                    comparison = comparison,
                    emptyText = "No differences",
                )
            }.fold(
                onSuccess = { it },
                onFailure = { RefreshState(error = it.userMessage()) },
            )

            applyStateLater(generation, result.copy(telemetry = telemetry))
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
        state.telemetry.measure("applyUi") {
            if (state.error == null) {
                repositoryRoot = state.repositoryRoot
                updateWorktreeModel(state.worktrees, state.selected)
                fileTree.setEmptyText(state.emptyText)
                fileTree.setEntries(state.comparison?.entries.orEmpty())
            } else {
                clearFiles(state.error)
            }

            setControlsEnabled(enabled = true)
        }
        LOG.info(state.telemetry.summary(success = state.error == null))
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
        worktreeComboBox.toolTipText = selected?.path?.toString() ?: "No worktrees"
    }

    private fun selectedWorktree(): WorktreeInfo? =
        worktreeComboBox.selectedItem as? WorktreeInfo

    private fun currentWorktrees(): List<WorktreeInfo> =
        (0 until worktreeModel.size).map { worktreeModel.getElementAt(it) }

    private fun clearFiles(message: String) {
        fileTree.clear()
        fileTree.setEmptyText(message)
    }

    private fun setBusy(message: String) {
        fileTree.setEmptyText(message)
        setControlsEnabled(false)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        refreshEnabled = enabled
        optionsEnabled = enabled && worktreeModel.size > 0
        worktreeComboBox.isEnabled = optionsEnabled
        fileTree.setEnabled(enabled)
        refreshToolbar.updateActionsAsync()
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

    private fun createRefreshToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup().apply {
            add(refreshAction)
        }
        return ActionManager.getInstance()
            .createActionToolbar("WorktreeDiff.Controls", actionGroup, true)
            .apply {
                setReservePlaceAutoPopupIcon(false)
            }
    }

    private fun createControlsToolbar(): JComponent =
        JPanel(BorderLayout()).apply {
            worktreeComboBox.minimumSize = Dimension(0, worktreeComboBox.preferredSize.height)
            add(worktreeComboBox, BorderLayout.CENTER)
            add(refreshToolbar.component, BorderLayout.EAST)
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
    val emptyText: String = "No differences",
    val error: String? = null,
    val telemetry: RefreshTelemetry = RefreshTelemetry("refresh"),
)

private fun Throwable.userMessage(): String =
    when (this) {
        is GitCommandException -> message ?: "Git command failed."
        else -> message ?: "Refresh failed."
    }
