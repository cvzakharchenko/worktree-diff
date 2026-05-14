package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
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
    private val tree = object : ChangesListView(project, false) {}
    private val filePathFactory = VcsContextFactory.getInstance()

    private var entries: List<FileComparison> = emptyList()
    private var filePathsByRelativePath: Map<String, FilePath> = emptyMap()
    private var entriesByAbsolutePath: Map<Path, FileComparison> = emptyMap()
    private var selectedRelativePath: String? = null
    private var suppressSelectionOpen = false

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
    }

    init {
        tree.emptyText.text = "No differences"
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addSelectionListener {
            selectedEntry()?.let {
                selectedRelativePath = it.relativePath
                if (!suppressSelectionOpen) {
                    onOpenDiff()
                }
            }
        }
        tree.setDoubleClickAndEnterKeyHandler {
            if (selectedEntry() != null) {
                onOpenDiff()
            }
        }
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

        rebuildTree()
    }

    fun setEnabled(enabled: Boolean) {
        tree.isEnabled = enabled
    }

    fun clear() {
        entries = emptyList()
        filePathsByRelativePath = emptyMap()
        entriesByAbsolutePath = emptyMap()
        selectedRelativePath = null
        rebuildTree()
    }

    fun selectedEntry(): FileComparison? {
        val filePath = selectedFilePath() ?: return null
        return entriesByAbsolutePath[filePath.normalizedAbsolutePath()]
    }

    fun selectedVisibleIndex(): Int {
        val entry = selectedEntry() ?: return -1
        return entries.indexOfFirst { it.relativePath == entry.relativePath }
    }

    fun visibleEntries(): List<FileComparison> = entries

    fun selectEntry(relativePath: String) {
        selectedRelativePath = relativePath
        filePathsByRelativePath[relativePath]?.let {
            withSelectionOpenSuppressed {
                tree.selectFile(it)
            }
        }
    }

    private fun rebuildTree() {
        val previousSelection = selectedEntry()?.relativePath ?: selectedRelativePath
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

    private fun selectedFilePath(): FilePath? {
        val node = tree.selectionPath?.lastPathComponent as? ChangesBrowserNode<*>
        return node?.userObject as? FilePath
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
}

private fun FilePath.normalizedAbsolutePath(): Path =
    ioFile.toPath().normalizedAbsolutePath()

private fun Path.normalizedAbsolutePath(): Path =
    toAbsolutePath().normalize()
