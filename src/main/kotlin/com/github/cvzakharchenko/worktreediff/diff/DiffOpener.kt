package com.github.cvzakharchenko.worktreediff.diff

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

class DiffOpener(
    private val project: Project,
) {

    fun open(entries: List<FileComparison>, selectedIndex: Int) {
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
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
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
