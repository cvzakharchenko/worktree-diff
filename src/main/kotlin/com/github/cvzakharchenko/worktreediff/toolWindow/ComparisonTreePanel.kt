package com.github.cvzakharchenko.worktreediff.toolWindow

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal class ComparisonTreePanel(
    private val onOpenDiff: () -> Unit,
) {
    private val searchField = SearchTextField().apply {
        textEditor.emptyText.text = "Filter"
        addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = rebuildTree()
            override fun removeUpdate(event: DocumentEvent) = rebuildTree()
            override fun changedUpdate(event: DocumentEvent) = rebuildTree()
        })
    }
    private val showPaths = JCheckBox("Show paths").apply {
        addActionListener { rebuildTree() }
    }
    private val colorStatuses = JCheckBox("Color statuses").apply {
        isSelected = true
        addActionListener { tree.repaint() }
    }
    private val expandButton = JButton(AllIcons.Actions.Expandall).apply {
        toolTipText = "Expand All"
        addActionListener { TreeUtil.expandAll(tree) }
    }
    private val collapseButton = JButton(AllIcons.Actions.Collapseall).apply {
        toolTipText = "Collapse All"
        addActionListener { TreeUtil.collapseAll(tree, 0) }
    }

    private val rootNode = DefaultMutableTreeNode(RootNode)
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No differences"
        cellRenderer = ComparisonTreeRenderer()
        selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private var entries: List<FileComparison> = emptyList()
    private var visibleEntries: List<FileComparison> = emptyList()
    private var selectedRelativePath: String? = null

    val component: JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        add(createToolbar(), BorderLayout.NORTH)
        add(Wrapper(ScrollPaneFactory.createScrollPane(tree)), BorderLayout.CENTER)
    }

    init {
        TreeSpeedSearch.installOn(tree, true) { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            when (val value = node?.userObject) {
                is EntryNode -> value.entry.relativePath
                is DirectoryNode -> value.path
                else -> ""
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1 && selectedEntry() != null) {
                    onOpenDiff()
                }
            }
        })
        tree.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openDiff")
        tree.actionMap.put("openDiff", object : javax.swing.AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) {
                if (selectedEntry() != null) {
                    onOpenDiff()
                }
            }
        })
    }

    fun setEntries(newEntries: List<FileComparison>) {
        entries = newEntries
        if (selectedRelativePath !in entries.map { it.relativePath }) {
            selectedRelativePath = null
        }
        rebuildTree()
    }

    fun setEnabled(enabled: Boolean) {
        searchField.isEnabled = enabled
        showPaths.isEnabled = enabled
        colorStatuses.isEnabled = enabled
        expandButton.isEnabled = enabled
        collapseButton.isEnabled = enabled
        tree.isEnabled = enabled
    }

    fun clear() {
        entries = emptyList()
        selectedRelativePath = null
        rebuildTree()
    }

    fun selectedEntry(): FileComparison? =
        selectedNode()?.entry

    fun selectedVisibleIndex(): Int {
        val entry = selectedEntry() ?: return -1
        return visibleEntries.indexOfFirst { it.relativePath == entry.relativePath }
    }

    fun visibleEntries(): List<FileComparison> = visibleEntries

    fun selectEntry(relativePath: String) {
        selectedRelativePath = relativePath
        selectVisibleNode(relativePath)
    }

    private fun createToolbar(): JComponent {
        val options = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(showPaths)
            add(colorStatuses)
            add(expandButton)
            add(collapseButton)
        }

        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(searchField, BorderLayout.NORTH)
            add(options, BorderLayout.SOUTH)
        }
    }

    private fun rebuildTree() {
        val previousSelection = selectedEntry()?.relativePath ?: selectedRelativePath
        selectedRelativePath = previousSelection

        rootNode.removeAllChildren()
        visibleEntries = filteredEntries()

        if (showPaths.isSelected) {
            visibleEntries.forEach { rootNode.add(DefaultMutableTreeNode(EntryNode(it, it.relativePath))) }
        } else {
            buildDirectoryTree(visibleEntries)
        }

        treeModel.reload()
        TreeUtil.expandAll(tree)
        previousSelection?.let { selectVisibleNode(it) }
    }

    private fun filteredEntries(): List<FileComparison> {
        val query = searchField.text.trim()
        if (query.isEmpty()) {
            return entries
        }

        return entries.filter {
            it.relativePath.contains(query, ignoreCase = true) ||
                it.relativePath.substringAfterLast('/').contains(query, ignoreCase = true)
        }
    }

    private fun buildDirectoryTree(entries: List<FileComparison>) {
        val directories = mutableMapOf<String, DefaultMutableTreeNode>()
        for (entry in entries) {
            val parts = entry.relativePath.split('/').filter { it.isNotBlank() }
            var parent = rootNode
            var currentPath = ""

            for (part in parts.dropLast(1)) {
                currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                parent = directories.getOrPut(currentPath) {
                    DefaultMutableTreeNode(DirectoryNode(part, currentPath)).also(parent::add)
                }
            }

            val name = parts.lastOrNull() ?: entry.relativePath
            parent.add(DefaultMutableTreeNode(EntryNode(entry, name)))
        }
    }

    private fun selectVisibleNode(relativePath: String) {
        val node = findEntryNode(rootNode, relativePath) ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findEntryNode(parent: DefaultMutableTreeNode, relativePath: String): DefaultMutableTreeNode? {
        val children = parent.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as DefaultMutableTreeNode
            val value = child.userObject
            if (value is EntryNode && value.entry.relativePath == relativePath) {
                return child
            }
            findEntryNode(child, relativePath)?.let { return it }
        }
        return null
    }

    private fun selectedNode(): EntryNode? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        return node?.userObject as? EntryNode
    }

    private inner class ComparisonTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode
            when (val data = node?.userObject) {
                is DirectoryNode -> {
                    icon = if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
                    append(data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }

                is EntryNode -> {
                    icon = FileTypeManager.getInstance()
                        .getFileTypeByFileName(data.entry.relativePath.substringAfterLast('/'))
                        .icon
                    append(data.displayName, attributesFor(data.entry, selected))
                    if (!showPaths.isSelected) {
                        append("  ${data.entry.statusLabel()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }

                else -> append("", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }

    private fun attributesFor(entry: FileComparison, selected: Boolean): SimpleTextAttributes =
        when {
            selected || !colorStatuses.isSelected -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            else -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, entry.status().color)
        }
}

private object RootNode

private data class DirectoryNode(
    val name: String,
    val path: String,
)

private data class EntryNode(
    val entry: FileComparison,
    val displayName: String,
)

private fun FileComparison.status(): FileStatus =
    when {
        !leftExists && rightExists -> FileStatus.ADDED
        leftExists && !rightExists -> FileStatus.DELETED
        else -> FileStatus.MODIFIED
    }

private fun FileComparison.statusLabel(): String =
    when (status()) {
        FileStatus.ADDED -> "added"
        FileStatus.DELETED -> "removed"
        else -> "changed"
    }
