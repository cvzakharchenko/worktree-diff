package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.diff.EntryEditService
import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.ide.CommonActionsManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesGroupingSupport
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.ui.ScrollPaneFactory
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.TreeSelectionModel

internal class ComparisonTreePanel(
    private val project: Project,
    private val onOpenDiff: () -> Unit,
) {
    private var entries: List<FileComparison> = emptyList()
    private var filePathsByRelativePath: Map<String, FilePath> = emptyMap()
    private var entriesByAbsolutePath: Map<Path, FileComparison> = emptyMap()
    private var selectedRelativePath: String? = null
    private var suppressSelectionOpen = false

    private val tree = object : ChangesListView(project, false) {
        override fun rebuildTree() {
            rebuildTreeModel()
        }
    }
    private val filePathFactory = VcsContextFactory.getInstance()

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    }

    init {
        tree.emptyText.text = "No differences"
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.addSelectionListener {
            singleSelectedEntry()?.let {
                selectedRelativePath = it.relativePath
                if (!suppressSelectionOpen) {
                    onOpenDiff()
                }
            }
        }
        tree.setDoubleClickAndEnterKeyHandler {
            if (singleSelectedEntry() != null) {
                onOpenDiff()
            }
        }
        installPopupActions()
    }

    fun setEmptyText(message: String) {
        tree.emptyText.text = message
    }

    fun setEntries(newEntries: List<FileComparison>) {
        entries = newEntries
        filePathsByRelativePath = entries.associate { entry ->
            entry.relativePath to filePathFactory.createFilePath(entry.leftPath, false)
        }
        entriesByAbsolutePath = entries.associateBy { it.leftPath.normalizedAbsolutePath() }

        if (selectedRelativePath !in filePathsByRelativePath.keys) {
            selectedRelativePath = null
        }

        rebuildTreeModel()
    }

    fun setEnabled(enabled: Boolean) {
        tree.isEnabled = enabled
    }

    fun clear() {
        entries = emptyList()
        filePathsByRelativePath = emptyMap()
        entriesByAbsolutePath = emptyMap()
        selectedRelativePath = null
        rebuildTreeModel()
    }

    fun selectedEntry(): FileComparison? = singleSelectedEntry()

    fun selectedVisibleIndex(): Int {
        val entry = singleSelectedEntry() ?: return -1
        return entries.indexOfFirst { it.relativePath == entry.relativePath }
    }

    fun visibleEntries(): List<FileComparison> = entries

    fun titleActions(): List<AnAction> {
        val commonActions = CommonActionsManager.getInstance()
        return listOf(
            GroupByActionGroup(),
            commonActions.createExpandAllHeaderAction(tree.treeExpander, tree),
            commonActions.createCollapseAllHeaderAction(tree.treeExpander, tree),
        )
    }

    fun selectEntry(relativePath: String) {
        selectedRelativePath = relativePath
        filePathsByRelativePath[relativePath]?.let {
            withSelectionOpenSuppressed {
                tree.selectFile(it)
            }
        }
    }

    private fun rebuildTreeModel() {
        val previousSelection = singleSelectedEntry()?.relativePath ?: selectedRelativePath
        selectedRelativePath = previousSelection

        val model = TreeModelBuilder.buildFromFilePaths(
            project,
            tree.grouping,
            filePathsByRelativePath.values,
        )
        withSelectionOpenSuppressed {
            tree.updateTreeModel(model, ChangesTree.KEEP_SELECTED_OBJECTS)
            tree.expandAll()
            previousSelection?.let(::selectEntry)
        }
    }

    private fun singleSelectedEntry(): FileComparison? {
        val paths = tree.selectionPaths ?: return null
        if (paths.size != 1) {
            return null
        }

        val node = paths[0].lastPathComponent as? ChangesBrowserNode<*> ?: return null
        if (node.childCount != 0) {
            return null
        }

        val filePath = node.userObject as? FilePath ?: return null
        return entriesByAbsolutePath[filePath.normalizedAbsolutePath()]
    }

    private fun collectSelectedEntries(): List<FileComparison> {
        val paths = tree.selectionPaths ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val selectedEntries = mutableListOf<FileComparison>()

        for (path in paths) {
            val node = path.lastPathComponent as? ChangesBrowserNode<*> ?: continue
            collectEntriesUnder(node, seen, selectedEntries)
        }

        return selectedEntries
    }

    private fun collectEntriesUnder(
        root: ChangesBrowserNode<*>,
        seen: MutableSet<String>,
        selectedEntries: MutableList<FileComparison>,
    ) {
        val stack = ArrayDeque<ChangesBrowserNode<*>>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.childCount == 0) {
                val filePath = current.userObject as? FilePath ?: continue
                val entry = entriesByAbsolutePath[filePath.normalizedAbsolutePath()] ?: continue
                if (seen.add(entry.relativePath)) {
                    selectedEntries.add(entry)
                }
            } else {
                for (i in 0 until current.childCount) {
                    val child = current.getChildAt(i) as? ChangesBrowserNode<*> ?: continue
                    stack.addLast(child)
                }
            }
        }
    }

    private fun installPopupActions() {
        val group = DefaultActionGroup().apply {
            add(EntryEditAction(EntryEditService.Mode.ACCEPT_LEFT))
            add(EntryEditAction(EntryEditService.Mode.ACCEPT_RIGHT))
            add(EntryEditAction(EntryEditService.Mode.SWAP))
        }
        tree.installPopupHandler(group)
    }

    private fun withSelectionOpenSuppressed(action: () -> Unit) {
        val wasSuppressed = suppressSelectionOpen
        suppressSelectionOpen = true
        try {
            action()
        } finally {
            suppressSelectionOpen = wasSuppressed
        }
    }

    private inner class GroupByActionGroup : DefaultActionGroup("Group By", true) {
        init {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            templatePresentation.description = "Group files"
            add(GroupingToggleAction("Directory", ChangesGroupingSupport.DIRECTORY_GROUPING))
            add(GroupingToggleAction("Module", ChangesGroupingSupport.MODULE_GROUPING))
            add(GroupingToggleAction("Repository", ChangesGroupingSupport.REPOSITORY_GROUPING))
        }
    }

    private inner class GroupingToggleAction(
        text: String,
        private val groupingKey: String,
    ) : DumbAwareToggleAction(text) {
        override fun update(event: AnActionEvent) {
            super.update(event)
            event.presentation.isEnabled = tree.groupingSupport.isAvailable(groupingKey)
        }

        override fun isSelected(event: AnActionEvent): Boolean =
            tree.groupingSupport.get(groupingKey)

        override fun setSelected(event: AnActionEvent, state: Boolean) {
            tree.groupingSupport.set(groupingKey, state)
            rebuildTreeModel()
        }
    }

    private inner class EntryEditAction(
        private val mode: EntryEditService.Mode,
    ) : DumbAwareAction(mode.actionText) {

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = tree.isEnabled && collectSelectedEntries().isNotEmpty()
        }

        override fun actionPerformed(event: AnActionEvent) {
            val selectedEntries = collectSelectedEntries()
            if (selectedEntries.isNotEmpty()) {
                project.service<EntryEditService>().apply(selectedEntries, mode)
            }
        }
    }
}

private fun FilePath.normalizedAbsolutePath(): Path =
    ioFile.toPath().normalizedAbsolutePath()

private fun Path.normalizedAbsolutePath(): Path =
    toAbsolutePath().normalize()
