package com.github.cvzakharchenko.worktreediff.git

import java.nio.file.Files
import java.nio.file.Path

data class WorktreeInfo(
    val path: Path,
    val head: String?,
    val branch: String?,
    val bare: Boolean = false,
    val detached: Boolean = false,
    val locked: Boolean = false,
    val prunable: Boolean = false,
) {
    val displayName: String
        get() {
            val branchName = branch
                ?.removePrefix("refs/heads/")
                ?.takeIf { it.isNotBlank() }
            val revision = head?.take(8)?.takeIf { it.isNotBlank() }
            val suffix = when {
                branchName != null -> branchName
                revision != null -> revision
                else -> "unknown"
            }
            return "${path.fileName} ($suffix)"
        }

    override fun toString(): String = displayName
}

data class FileComparison(
    val relativePath: String,
    val leftPath: Path,
    val rightPath: Path,
    val leftExists: Boolean,
    val rightExists: Boolean,
    val headChanged: Boolean,
    val leftLocallyChanged: Boolean,
    val rightLocallyChanged: Boolean,
) {
    val locallyChanged: Boolean
        get() = leftLocallyChanged || rightLocallyChanged

    override fun toString(): String = relativePath
}

data class ComparisonResult(
    val entries: List<FileComparison>,
)

internal fun Path.existsOnDisk(): Boolean = Files.exists(this)
