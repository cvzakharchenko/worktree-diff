package com.github.cvzakharchenko.worktreediff.git

import java.nio.file.Path

class WorktreeService(
    private val git: GitCli = GitCli(),
) {

    fun findRepositoryRoot(startDirectory: Path): Path? {
        val output = try {
            git.runText(startDirectory, "rev-parse", "--show-toplevel")
        } catch (_: GitCommandException) {
            return null
        }

        return output
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.let { Path.of(it).toAbsolutePath().normalizeExisting() }
    }

    fun listOtherWorktrees(currentRoot: Path): List<WorktreeInfo> {
        val normalizedCurrentRoot = currentRoot.toAbsolutePath().normalizeExisting()
        return parseWorktreeList(git.runBytes(currentRoot, "worktree", "list", "--porcelain", "-z"))
            .filterNot { it.bare }
            .filterNot { it.path.toAbsolutePath().normalizeExisting() == normalizedCurrentRoot }
            .sortedBy { it.path.toString() }
    }

    fun currentHead(root: Path): String? =
        git.runText(root, "rev-parse", "HEAD").trim().takeIf { it.isNotBlank() }

    internal fun parseWorktreeList(output: ByteArray): List<WorktreeInfo> {
        val worktrees = mutableListOf<WorktreeInfo>()
        var path: Path? = null
        var head: String? = null
        var branch: String? = null
        var bare = false
        var detached = false
        var locked = false
        var prunable = false

        fun flush() {
            val currentPath = path ?: return
            worktrees += WorktreeInfo(
                path = currentPath,
                head = head,
                branch = branch,
                bare = bare,
                detached = detached,
                locked = locked,
                prunable = prunable,
            )
            path = null
            head = null
            branch = null
            bare = false
            detached = false
            locked = false
            prunable = false
        }

        for (token in output.toNulSeparatedStrings()) {
            if (token.isEmpty()) {
                flush()
                continue
            }

            val key = token.substringBefore(' ')
            val value = token.substringAfter(' ', "")
            when (key) {
                "worktree" -> path = Path.of(value).toAbsolutePath().normalizeExisting()
                "HEAD" -> head = value
                "branch" -> branch = value
                "bare" -> bare = true
                "detached" -> detached = true
                "locked" -> locked = true
                "prunable" -> prunable = true
            }
        }
        flush()

        return worktrees
    }
}

internal fun Path.normalizeExisting(): Path =
    runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
