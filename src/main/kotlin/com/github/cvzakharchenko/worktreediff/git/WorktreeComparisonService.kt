package com.github.cvzakharchenko.worktreediff.git

import com.github.cvzakharchenko.worktreediff.telemetry.RefreshTelemetry
import com.github.cvzakharchenko.worktreediff.telemetry.measure
import com.github.cvzakharchenko.worktreediff.telemetry.metric
import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class WorktreeComparisonService(
    private val git: GitCli = GitCli(),
    private val worktreeService: WorktreeService = WorktreeService(git),
) {

    fun compare(
        currentRoot: Path,
        selectedWorktree: WorktreeInfo,
        includeLocalChanges: Boolean,
        ignoreLineEndings: Boolean = false,
        ignoreStagedChanges: Boolean = false,
        ignoreHeadChanges: Boolean = false,
        telemetry: RefreshTelemetry? = null,
    ): ComparisonResult {
        val selectedRoot = selectedWorktree.path
        val currentHeadFuture = if (ignoreHeadChanges) {
            CompletableFuture.completedFuture(null)
        } else {
            asyncMeasured("leftHead", telemetry) {
                worktreeService.currentHead(currentRoot)
            }
        }
        val selectedHeadFuture = if (ignoreHeadChanges) {
            CompletableFuture.completedFuture(null)
        } else {
            asyncMeasured("rightHead", telemetry) {
                worktreeService.currentHead(selectedRoot)
            }
        }
        val leftLocalChangesFuture = asyncMeasured("leftStatus", telemetry) {
            localChangePaths(currentRoot, ignoreStagedChanges)
        }
        val rightLocalChangesFuture = asyncMeasured("rightStatus", telemetry) {
            localChangePaths(selectedRoot, ignoreStagedChanges)
        }

        val headPaths = if (ignoreHeadChanges) {
            emptySet()
        } else {
            headDiffPaths(
                currentRoot = currentRoot,
                currentHead = currentHeadFuture.await(),
                selectedHead = selectedHeadFuture.await(),
                telemetry = telemetry,
            )
        }
        val leftLocalChanges = leftLocalChangesFuture.await()
        val rightLocalChanges = rightLocalChangesFuture.await()

        val candidates = telemetry.measure("buildCandidates") {
            sortedSetOf<String>().apply {
                addAll(headPaths)
                addAll(leftLocalChanges)
                addAll(rightLocalChanges)
            }
        }
        telemetry.metric("headPaths", headPaths.size)
        telemetry.metric("leftLocalChanges", leftLocalChanges.size)
        telemetry.metric("rightLocalChanges", rightLocalChanges.size)
        telemetry.metric("candidates", candidates.size)

        val entries = telemetry.measure("filterAndBuildEntries") {
            candidates
                .asSequence()
                .filter {
                    includeLocalChanges ||
                        !filesEqual(resolveGitPath(currentRoot, it), resolveGitPath(selectedRoot, it), ignoreLineEndings)
                }
                .map {
                    val leftPath = resolveGitPath(currentRoot, it)
                    val rightPath = resolveGitPath(selectedRoot, it)
                    FileComparison(
                        relativePath = it,
                        leftPath = leftPath,
                        rightPath = rightPath,
                        leftExists = leftPath.existsOnDisk(),
                        rightExists = rightPath.existsOnDisk(),
                        headChanged = it in headPaths,
                        leftLocallyChanged = it in leftLocalChanges,
                        rightLocallyChanged = it in rightLocalChanges,
                    )
                }
                .toList()
        }
        telemetry.metric("entries", entries.size)

        return ComparisonResult(entries)
    }

    private fun headDiffPaths(
        currentRoot: Path,
        currentHead: String?,
        selectedHead: String?,
        telemetry: RefreshTelemetry?,
    ): Set<String> {
        if (currentHead == null || selectedHead == null) {
            return emptySet()
        }

        return telemetry.measure("headDiff") {
            parsePathList(
                git.runBytes(
                    currentRoot,
                    "diff",
                    "--name-only",
                    "-z",
                    "--no-renames",
                    currentHead,
                    selectedHead,
                ),
            )
        }
    }

    private fun localChangePaths(root: Path, ignoreStagedChanges: Boolean): Set<String> =
        parseStatusPaths(
            git.runBytes(
                root,
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=all",
                "--no-renames",
            ),
            ignoreStagedChanges,
        )

    internal fun parsePathList(output: ByteArray): Set<String> =
        output.toNulSeparatedStrings()
            .asSequence()
            .filter { it.isNotBlank() }
            .map { normalizeGitPath(it) }
            .filter { it.isNotBlank() }
            .toSet()

    internal fun parseStatusPaths(output: ByteArray, ignoreStagedChanges: Boolean = false): Set<String> =
        output.toNulSeparatedStrings()
            .asSequence()
            .filter { it.length >= 4 }
            .filterNot { it.startsWith("!!") }
            .filter { shouldIncludeStatusEntry(it, ignoreStagedChanges) }
            .map { normalizeGitPath(it.substring(3)) }
            .filter { it.isNotBlank() }
            .toSet()

    private fun shouldIncludeStatusEntry(entry: String, ignoreStagedChanges: Boolean): Boolean =
        !ignoreStagedChanges ||
            entry.startsWith("??") ||
            entry[1] != ' '

    internal fun resolveGitPath(root: Path, relativePath: String): Path {
        val resolved = root.resolve(relativePath.replace('/', root.fileSystem.separator.single())).normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        val absolute = resolved.toAbsolutePath().normalize()
        require(absolute.startsWith(normalizedRoot)) {
            "Git path escapes repository root: $relativePath"
        }
        return absolute
    }

    internal fun filesEqual(left: Path, right: Path, ignoreLineEndings: Boolean = false): Boolean {
        val leftExists = left.existsOnDisk()
        val rightExists = right.existsOnDisk()
        if (leftExists != rightExists) {
            return false
        }
        if (!leftExists) {
            return true
        }

        val leftIsDirectory = left.isDirectory()
        val rightIsDirectory = right.isDirectory()
        if (leftIsDirectory || rightIsDirectory) {
            return leftIsDirectory && rightIsDirectory
        }
        if (!left.isRegularFile() || !right.isRegularFile()) {
            return false
        }
        if (ignoreLineEndings) {
            return filesEqualIgnoringLineEndings(left, right)
        }
        if (Files.size(left) != Files.size(right)) {
            return false
        }

        return binaryFilesEqual(left, right)
    }

    private fun binaryFilesEqual(left: Path, right: Path): Boolean {
        BufferedInputStream(Files.newInputStream(left)).use { leftInput ->
            BufferedInputStream(Files.newInputStream(right)).use { rightInput ->
                while (true) {
                    val leftByte = leftInput.read()
                    val rightByte = rightInput.read()
                    if (leftByte != rightByte) {
                        return false
                    }
                    if (leftByte == -1) {
                        return true
                    }
                }
            }
        }
    }

    private fun filesEqualIgnoringLineEndings(left: Path, right: Path): Boolean {
        PushbackInputStream(BufferedInputStream(Files.newInputStream(left)), 1).use { leftInput ->
            PushbackInputStream(BufferedInputStream(Files.newInputStream(right)), 1).use { rightInput ->
                while (true) {
                    val leftByte = readTextByte(leftInput)
                    val rightByte = readTextByte(rightInput)
                    if (leftByte != rightByte) {
                        return false
                    }
                    if (leftByte == -1) {
                        return true
                    }
                }
            }
        }
    }

    private fun readTextByte(input: PushbackInputStream): Int {
        val byte = input.read()
        if (byte != '\r'.code) {
            return byte
        }

        val next = input.read()
        if (next != -1 && next != '\n'.code) {
            input.unread(next)
        }
        return '\n'.code
    }

    private fun normalizeGitPath(path: String): String =
        path.trimEnd('/').replace('\\', '/')
}

private fun <T> asyncMeasured(
    name: String,
    telemetry: RefreshTelemetry?,
    action: () -> T,
): CompletableFuture<T> =
    CompletableFuture.supplyAsync {
        telemetry.measure(name, action)
    }

private fun <T> CompletableFuture<T>.await(): T =
    try {
        join()
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }
