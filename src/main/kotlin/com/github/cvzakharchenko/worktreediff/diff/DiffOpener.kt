package com.github.cvzakharchenko.worktreediff.diff

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

class DiffOpener(
    private val project: Project,
) {

    fun open(
        entries: List<FileComparison>,
        selectedIndex: Int,
        onSelectedEntryChanged: (String) -> Unit,
    ) {
        if (entries.isEmpty() || selectedIndex !in entries.indices) {
            return
        }

        val requests = entries.map { entry ->
            SimpleDiffRequest(
                entry.relativePath,
                contentFor(entry.leftPath, entry.leftExists),
                contentFor(entry.rightPath, entry.rightExists),
                "Current worktree",
                "Selected worktree",
            )
        }
        val chain = SimpleDiffRequestChain(requests, selectedIndex)
        onSelectedEntryChanged(entries[selectedIndex].relativePath)
        FileEditorManager.getInstance(project).openFile(
            TrackingChainDiffVirtualFile(chain, entries, onSelectedEntryChanged),
            true,
        )
    }

    private fun contentFor(path: Path, exists: Boolean): DiffContent {
        val factory = DiffContentFactory.getInstance()
        if (!exists) {
            return factory.createEmpty()
        }

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        return when {
            virtualFile != null -> factory.create(project, virtualFile)
            else -> factory.createEmpty()
        }
    }
}

private class TrackingChainDiffVirtualFile(
    private val requestChain: SimpleDiffRequestChain,
    private val entries: List<FileComparison>,
    private val onSelectedEntryChanged: (String) -> Unit,
) : ChainDiffVirtualFile(requestChain, "Worktree Diff") {

    override fun createProcessor(project: Project): DiffRequestProcessor =
        TrackingDiffRequestChainProcessor(project, requestChain, entries, onSelectedEntryChanged)
}

private class TrackingDiffRequestChainProcessor(
    project: Project,
    chain: SimpleDiffRequestChain,
    private val entries: List<FileComparison>,
    private val onSelectedEntryChanged: (String) -> Unit,
) : CacheDiffRequestChainProcessor(project, chain) {
    private var currentIndex = chain.index.coerceIn(entries.indices)

    override fun setCurrentRequest(index: Int) {
        if (index !in entries.indices) {
            return
        }

        currentIndex = index
        super.setCurrentRequest(index)
        notifyCurrentEntry()
    }

    override fun goToNextChange(fromDifferences: Boolean) {
        if (currentIndex >= entries.lastIndex) {
            return
        }

        currentIndex += 1
        super.goToNextChange(fromDifferences)
        notifyCurrentEntry()
    }

    override fun goToPrevChange(fromDifferences: Boolean) {
        if (currentIndex <= 0) {
            return
        }

        currentIndex -= 1
        super.goToPrevChange(fromDifferences)
        notifyCurrentEntry()
    }

    private fun notifyCurrentEntry() {
        onSelectedEntryChanged(entries[currentIndex].relativePath)
    }
}
