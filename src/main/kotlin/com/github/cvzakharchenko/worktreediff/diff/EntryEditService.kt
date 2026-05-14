package com.github.cvzakharchenko.worktreediff.diff

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UnexpectedUndoException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

private val LOG = Logger.getInstance(EntryEditService::class.java)

@Service(Service.Level.PROJECT)
class EntryEditService(
    private val project: Project,
) {
    private val operationLock = Any()

    enum class Mode(
        val actionText: String,
    ) {
        ACCEPT_LEFT("Accept Left"),
        ACCEPT_RIGHT("Accept Right"),
        SWAP("Swap"),
        ;

        fun commandName(fileCount: Int): String =
            if (fileCount == 1) actionText else "$actionText $fileCount Files"
    }

    fun apply(
        entries: List<FileComparison>,
        mode: Mode,
    ) {
        val normalizedEntries = entries.distinctBy { it.relativePath }
        if (normalizedEntries.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                synchronized(operationLock) {
                    applySynchronously(normalizedEntries, mode)
                }
            }.onFailure {
                LOG.warn("Failed to apply ${mode.actionText}", it)
                notifyError("${mode.actionText} failed: ${it.userMessage(mode)}")
            }
        }
    }

    internal fun applySynchronously(
        entries: List<FileComparison>,
        mode: Mode,
    ): EntryEditChange? {
        val normalizedEntries = entries.distinctBy { it.relativePath }
        if (normalizedEntries.isEmpty()) {
            return null
        }

        runOnEdtAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        val change = prepareChange(normalizedEntries, mode)
        if (!change.hasChanges) {
            return null
        }

        runOnEdtAndWait {
            applyStatesWithRollback(change, change.after, change.before)
            val commandProcessor = CommandProcessor.getInstance()
            commandProcessor.executeCommand(
                project,
                {
                    commandProcessor.markCurrentCommandAsGlobal(project)
                    UndoManager.getInstance(project).undoableActionPerformed(
                        EntryEditUndoableAction(project, change),
                    )
                },
                mode.commandName(normalizedEntries.size),
                Any(),
                UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION,
            )
        }

        notifyDiffsChanged(change)
        return change
    }

    private fun prepareChange(
        entries: List<FileComparison>,
        mode: Mode,
    ): EntryEditChange {
        val paths = entries.affectedPaths()
        val before = paths.associateWith(FileState::capture)
        val after = before.toMutableMap()

        entries.forEach { entry ->
            val leftPath = entry.leftPath.normalizedAbsolutePath()
            val rightPath = entry.rightPath.normalizedAbsolutePath()
            val leftBefore = before.getValue(leftPath)
            val rightBefore = before.getValue(rightPath)
            when (mode) {
                Mode.ACCEPT_LEFT -> {
                    after[leftPath] = leftBefore
                    after[rightPath] = leftBefore
                }

                Mode.ACCEPT_RIGHT -> {
                    after[leftPath] = rightBefore
                    after[rightPath] = rightBefore
                }

                Mode.SWAP -> {
                    after[leftPath] = rightBefore
                    after[rightPath] = leftBefore
                }
            }
        }

        return EntryEditChange(
            affectedRelativePaths = entries.mapTo(linkedSetOf()) { it.relativePath },
            affectedPaths = paths,
            before = before,
            after = after,
        )
    }

    private fun applyStatesWithRollback(
        change: EntryEditChange,
        target: Map<Path, FileState>,
        rollback: Map<Path, FileState>,
    ) {
        try {
            restoreStates(change.changedPaths, target)
        } catch (operationFailure: Throwable) {
            runCatching {
                restoreStates(change.changedPaths, rollback)
            }.onFailure(operationFailure::addSuppressed)
            throw operationFailure
        }
    }

    private fun restoreStates(
        paths: List<Path>,
        states: Map<Path, FileState>,
    ) {
        WriteAction.run<Throwable> {
            paths.forEach { path ->
                states.getValue(path).restoreToVfs(path)
            }
        }
    }

    private fun notifyDiffsChanged(change: EntryEditChange) {
        project.service<DiffOpener>().notifyEntriesChanged(change.affectedRelativePaths)
    }

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("WorktreeDiff.Notifications")
            .createNotification("Worktree Diff", message, NotificationType.ERROR)
            .notify(project)
    }

    private fun Throwable.userMessage(mode: Mode): String =
        message ?: "Failed to apply ${mode.actionText}."

    private inner class EntryEditUndoableAction(
        private val project: Project,
        private val change: EntryEditChange,
    ) : BasicUndoableAction() {

        override fun undo() {
            restoreForUndo(change.before, change.after, "Failed to undo Worktree Diff file operation.")
        }

        override fun redo() {
            restoreForUndo(change.after, change.before, "Failed to redo Worktree Diff file operation.")
        }

        override fun isGlobal(): Boolean = true

        private fun restoreForUndo(
            target: Map<Path, FileState>,
            rollback: Map<Path, FileState>,
            fallbackMessage: String,
        ) {
            var failure: Throwable? = null
            runCatching {
                applyStatesWithRollback(change, target, rollback)
            }.onFailure {
                failure = it
            }

            notifyDiffsChanged(change)

            failure?.let {
                throw UnexpectedUndoException(it.message ?: fallbackMessage)
            }
        }
    }
}

internal data class EntryEditChange(
    val affectedRelativePaths: Set<String>,
    val affectedPaths: List<Path>,
    val before: Map<Path, FileState>,
    val after: Map<Path, FileState>,
) {
    val changedPaths: List<Path>
        get() = affectedPaths.filter { path ->
            !before.getValue(path).hasSameContentAs(after.getValue(path))
        }

    val hasChanges: Boolean
        get() = changedPaths.isNotEmpty()
}

internal sealed class FileState {
    data class ExistingFile(val bytes: ByteArray) : FileState()
    data object Missing : FileState()

    fun restoreToVfs(path: Path) {
        when (this) {
            is ExistingFile -> writeFile(path, bytes)
            Missing -> deleteIfExists(path)
        }
    }

    fun hasSameContentAs(other: FileState): Boolean =
        when {
            this is Missing && other is Missing -> true
            this is ExistingFile && other is ExistingFile -> bytes.contentEquals(other.bytes)
            else -> false
        }

    companion object {
        fun capture(path: Path): FileState =
            when {
                !Files.exists(path) -> Missing
                Files.isRegularFile(path) -> ExistingFile(Files.readAllBytes(path))
                else -> error("Cannot operate on non-regular file: $path")
            }
    }
}

private fun writeFile(
    path: Path,
    bytes: ByteArray,
) {
    val localFileSystem = LocalFileSystem.getInstance()
    val existing = localFileSystem.refreshAndFindFileByNioFile(path)
    if (existing != null && existing.isValid) {
        require(!existing.isDirectory) {
            "Cannot replace directory with file content: $path"
        }
        existing.setBinaryContent(bytes)
        return
    }

    val parent = path.parent ?: error("Cannot determine parent directory for $path")
    Files.createDirectories(parent)
    val parentVirtualFile = VfsUtil.createDirectories(parent.toString())
        ?: error("Unable to materialize parent directory $parent in VFS")
    val childName = path.fileName.toString()
    val child = parentVirtualFile.findChild(childName)
    val file = when {
        child == null -> parentVirtualFile.createChildData(EntryEditService::class.java, childName)
        child.isDirectory -> error("Cannot replace directory with file content: $path")
        else -> child
    }
    file.setBinaryContent(bytes)
}

private fun deleteIfExists(path: Path) {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    if (!virtualFile.isValid) {
        return
    }
    require(!virtualFile.isDirectory) {
        "Cannot delete directory as a file operation: $path"
    }
    virtualFile.delete(EntryEditService::class.java)
}

private fun List<FileComparison>.affectedPaths(): List<Path> =
    distinctBy { it.relativePath }
        .flatMap { listOf(it.leftPath.normalizedAbsolutePath(), it.rightPath.normalizedAbsolutePath()) }
        .distinct()

private fun Path.normalizedAbsolutePath(): Path =
    toAbsolutePath().normalize()

private fun <T> runOnEdtAndWait(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return action()
    }

    var result: Result<T>? = null
    ApplicationManager.getApplication().invokeAndWait {
        result = runCatching(action)
    }
    return result?.getOrThrow() ?: error("EDT action did not produce a result.")
}
