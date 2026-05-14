package com.github.cvzakharchenko.worktreediff.diff

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class EntryEditServicePlatformTest : BasePlatformTestCase() {

    fun testAcceptLeftRegistersUndoAndUpdatesCachedDocument() {
        val root = Files.createTempDirectory("worktree-entry-edit-test-")
        val leftRoot = root.resolve("left").createDirectories()
        val rightRoot = root.resolve("right").createDirectories()
        val entry = comparison(leftRoot, rightRoot, "file.txt")
        entry.leftPath.writeText("left")
        entry.rightPath.writeText("right")
        val rightVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(entry.rightPath)!!
        val rightDocument = FileDocumentManager.getInstance().getDocument(rightVirtualFile)!!

        try {
            project.service<EntryEditService>().applySynchronously(
                listOf(entry),
                EntryEditService.Mode.ACCEPT_LEFT,
            )

            assertEquals("left", entry.rightPath.readText())
            assertEquals("left", rightDocument.text)
            assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))

            UndoManager.getInstance(project).undo(null)

            assertEquals("right", entry.rightPath.readText())
            assertEquals("right", rightDocument.text)
            assertTrue(UndoManager.getInstance(project).isRedoAvailable(null))

            UndoManager.getInstance(project).redo(null)

            assertEquals("left", entry.rightPath.readText())
            assertEquals("left", rightDocument.text)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testSwapExchangesExistingAndMissingFilesAndIsUndoable() {
        val root = Files.createTempDirectory("worktree-entry-edit-test-")
        val leftRoot = root.resolve("left").createDirectories()
        val rightRoot = root.resolve("right").createDirectories()
        val entry = comparison(leftRoot, rightRoot, "file.txt")
        entry.rightPath.writeText("right")

        try {
            project.service<EntryEditService>().applySynchronously(
                listOf(entry),
                EntryEditService.Mode.SWAP,
            )

            assertEquals("right", entry.leftPath.readText())
            assertFalse(entry.rightPath.exists())
            assertTrue(UndoManager.getInstance(project).isUndoAvailable(null))

            UndoManager.getInstance(project).undo(null)

            assertFalse(entry.leftPath.exists())
            assertEquals("right", entry.rightPath.readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testNoOpDoesNotRegisterUndo() {
        val root = Files.createTempDirectory("worktree-entry-edit-test-")
        val leftRoot = root.resolve("left").createDirectories()
        val rightRoot = root.resolve("right").createDirectories()
        val entry = comparison(leftRoot, rightRoot, "file.txt")
        entry.leftPath.writeText("same")
        entry.rightPath.writeText("same")

        try {
            val change = project.service<EntryEditService>().applySynchronously(
                listOf(entry),
                EntryEditService.Mode.ACCEPT_LEFT,
            )

            assertNull(change)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun comparison(
        leftRoot: Path,
        rightRoot: Path,
        relativePath: String,
    ): FileComparison {
        val leftPath = leftRoot.resolve(relativePath)
        val rightPath = rightRoot.resolve(relativePath)
        leftPath.parent?.createDirectories()
        rightPath.parent?.createDirectories()
        return FileComparison(
            relativePath = relativePath,
            leftPath = leftPath,
            rightPath = rightPath,
            leftExists = leftPath.exists(),
            rightExists = rightPath.exists(),
            headChanged = false,
            leftLocallyChanged = true,
            rightLocallyChanged = true,
        )
    }
}
