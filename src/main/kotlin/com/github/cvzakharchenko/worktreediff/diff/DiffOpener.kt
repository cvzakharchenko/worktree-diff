package com.github.cvzakharchenko.worktreediff.diff

import com.github.cvzakharchenko.worktreediff.git.FileComparison
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DiffOpener(
    private val project: Project,
) {
    private var currentDiffFile: TrackingChainDiffVirtualFile? = null
    private var currentProcessor: WorktreeDiffProcessor? = null
    private var currentSignature: List<DiffEntrySignature> = emptyList()

    fun open(
        entries: List<FileComparison>,
        selectedIndex: Int,
        onSelectedEntryChanged: (String) -> Unit,
    ) {
        if (entries.isEmpty() || selectedIndex !in entries.indices) {
            return
        }

        val signature = entries.map(::DiffEntrySignature)
        val existingProcessor = currentProcessor
        val existingFile = currentDiffFile
        if (existingFile != null &&
            existingProcessor != null &&
            !existingProcessor.isDisposedByUs &&
            signature == currentSignature
        ) {
            runWithWriteIntent {
                existingProcessor.setCurrentRequest(selectedIndex)
            }
            FileEditorManager.getInstance(project).openFile(existingFile, false)
            return
        }

        existingFile?.let {
            FileEditorManager.getInstance(project).closeFile(it)
        }

        val chain = SimpleDiffRequestChain.fromProducers(
            entries.map { WorktreeDiffRequestProducer(project, it) },
            selectedIndex,
        )
        val diffFile = TrackingChainDiffVirtualFile(
            requestChain = chain,
            entries = entries,
            onSelectedEntryChanged = onSelectedEntryChanged,
            onProcessorCreated = { processor ->
                currentProcessor = processor
            },
            onProcessorDisposed = { processor ->
                if (currentProcessor === processor) {
                    currentProcessor = null
                    currentDiffFile = null
                    currentSignature = emptyList()
                }
            },
        )

        currentDiffFile = diffFile
        currentSignature = signature
        onSelectedEntryChanged(entries[selectedIndex].relativePath)
        FileEditorManager.getInstance(project).openFile(diffFile, false)
    }

    fun notifyEntriesChanged(relativePaths: Set<String>) {
        if (relativePaths.isEmpty()) {
            return
        }
        currentProcessor?.notifyEntriesChanged(relativePaths)
    }
}

private fun runWithWriteIntent(action: () -> Unit) {
    WriteIntentReadAction.run { action() }
}

private data class DiffEntrySignature(
    val relativePath: String,
    val leftPath: Path,
    val rightPath: Path,
) {
    constructor(entry: FileComparison) : this(
        relativePath = entry.relativePath,
        leftPath = entry.leftPath.toAbsolutePath().normalize(),
        rightPath = entry.rightPath.toAbsolutePath().normalize(),
    )
}

private class WorktreeDiffRequestProducer(
    private val project: Project,
    private val entry: FileComparison,
) : DiffRequestProducer {

    override fun getName(): String = entry.relativePath

    override fun process(
        context: UserDataHolder,
        indicator: ProgressIndicator,
    ): DiffRequest =
        SimpleDiffRequest(
            entry.relativePath,
            contentFor(entry.leftPath),
            contentFor(entry.rightPath),
            "Current worktree",
            "Selected worktree",
        )

    private fun contentFor(path: Path): DiffContent {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        return if (virtualFile != null && virtualFile.isValid && !virtualFile.isDirectory) {
            DiffContentFactory.getInstance().create(project, virtualFile)
        } else {
            DiffContentFactory.getInstance().createEmpty()
        }
    }
}

private class TrackingChainDiffVirtualFile(
    private val requestChain: SimpleDiffRequestChain,
    private val entries: List<FileComparison>,
    private val onSelectedEntryChanged: (String) -> Unit,
    private val onProcessorCreated: (WorktreeDiffProcessor) -> Unit,
    private val onProcessorDisposed: (WorktreeDiffProcessor) -> Unit,
) : ChainDiffVirtualFile(requestChain, "Worktree Diff") {

    override fun createProcessor(project: Project): DiffRequestProcessor {
        val processor = WorktreeDiffProcessor(
            project = project,
            chain = requestChain,
            entries = entries,
            onSelectedEntryChanged = onSelectedEntryChanged,
            onDisposed = onProcessorDisposed,
        )
        onProcessorCreated(processor)
        return processor
    }
}

private class WorktreeDiffProcessor(
    project: Project,
    chain: SimpleDiffRequestChain,
    private val entries: List<FileComparison>,
    private val onSelectedEntryChanged: (String) -> Unit,
    private val onDisposed: (WorktreeDiffProcessor) -> Unit,
) : CacheDiffRequestChainProcessor(project, chain) {
    private var currentIndex = chain.index.coerceIn(entries.indices)

    @Volatile
    var isDisposedByUs: Boolean = false
        private set

    override fun dispose() {
        isDisposedByUs = true
        onDisposed(this)
        super.dispose()
    }

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

    fun notifyEntriesChanged(relativePaths: Set<String>) {
        if (entries.none { it.relativePath in relativePaths }) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (isDisposedByUs) {
                return@invokeLater
            }
            runWithWriteIntent {
                dropCaches()
                updateRequest(true)
            }
        }
    }

    private fun notifyCurrentEntry() {
        onSelectedEntryChanged(entries[currentIndex].relativePath)
    }
}
