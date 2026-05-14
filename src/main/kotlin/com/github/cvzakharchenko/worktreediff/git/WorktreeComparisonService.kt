package com.github.cvzakharchenko.worktreediff.git

import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.Path
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
    ): ComparisonResult {
        val selectedRoot = selectedWorktree.path
        val headPaths = headDiffPaths(currentRoot, selectedRoot)
        val leftLocalChanges = localChangePaths(currentRoot)
        val rightLocalChanges = localChangePaths(selectedRoot)

        val candidates = sortedSetOf<String>()
        candidates += headPaths
        candidates += leftLocalChanges
        candidates += rightLocalChanges

        val entries = candidates
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

        return ComparisonResult(entries)
    }

    private fun headDiffPaths(currentRoot: Path, selectedRoot: Path): Set<String> {
        val currentHead = worktreeService.currentHead(currentRoot)
        val selectedHead = worktreeService.currentHead(selectedRoot)
        if (currentHead == null || selectedHead == null) {
            return emptySet()
        }

        return parsePathList(
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

    private fun localChangePaths(root: Path): Set<String> =
        parseStatusPaths(
            git.runBytes(
                root,
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=all",
                "--no-renames",
            ),
        )

    internal fun parsePathList(output: ByteArray): Set<String> =
        output.toNulSeparatedStrings()
            .asSequence()
            .filter { it.isNotBlank() }
            .map { normalizeGitPath(it) }
            .filter { it.isNotBlank() }
            .toSet()

    internal fun parseStatusPaths(output: ByteArray): Set<String> =
        output.toNulSeparatedStrings()
            .asSequence()
            .filter { it.length >= 4 }
            .filterNot { it.startsWith("!!") }
            .map { normalizeGitPath(it.substring(3)) }
            .filter { it.isNotBlank() }
            .toSet()

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
