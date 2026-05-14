package com.github.cvzakharchenko.worktreediff.git

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorktreeComparisonServiceTest {
    private val tempRoots = mutableListOf<Path>()

    @After
    fun tearDown() {
        tempRoots.asReversed().forEach {
            it.toFile().deleteRecursively()
        }
    }

    @Test
    fun `equal worktrees produce empty comparison`() = withRepository { main, other ->
        val comparison = compare(main, other, includeLocalChanges = false)

        assertTrue(comparison.entries.isEmpty())
    }

    @Test
    fun `different tracked file is included`() = withRepository { main, other ->
        main.resolve("tracked.txt").writeText("changed in main\n")

        val comparison = compare(main, other, includeLocalChanges = false)

        assertEquals(listOf("tracked.txt"), comparison.entries.map { it.relativePath })
    }

    @Test
    fun `untracked non ignored file is included`() = withRepository { main, other ->
        other.resolve("new.txt").writeText("created in other\n")

        val comparison = compare(main, other, includeLocalChanges = false)

        assertEquals(listOf("new.txt"), comparison.entries.map { it.relativePath })
    }

    @Test
    fun `ignored file is excluded`() = withRepository { main, other ->
        main.resolve("ignored.txt").writeText("ignored in main\n")
        other.resolve("ignored.txt").writeText("ignored in other\n")

        val comparison = compare(main, other, includeLocalChanges = true)

        assertTrue(comparison.entries.isEmpty())
    }

    @Test
    fun `head difference made equal by local edit is hidden unless toggle is on`() = withRepository { main, other ->
        other.resolve("tracked.txt").writeText("other head\n")
        git(other, "commit", "-am", "change on other")
        main.resolve("tracked.txt").writeText("other head\n")

        val toggleOff = compare(main, other, includeLocalChanges = false)
        val toggleOn = compare(main, other, includeLocalChanges = true)

        assertTrue(toggleOff.entries.isEmpty())
        assertEquals(listOf("tracked.txt"), toggleOn.entries.map { it.relativePath })
    }

    @Test
    fun `equal untracked files are hidden unless toggle is on`() = withRepository { main, other ->
        main.resolve("same.txt").writeText("same\n")
        other.resolve("same.txt").writeText("same\n")

        val toggleOff = compare(main, other, includeLocalChanges = false)
        val toggleOn = compare(main, other, includeLocalChanges = true)

        assertTrue(toggleOff.entries.isEmpty())
        assertEquals(listOf("same.txt"), toggleOn.entries.map { it.relativePath })
    }

    @Test
    fun `line ending only differences are included by binary comparison`() = withRepository { main, other ->
        main.resolve("tracked.txt").writeText("one\r\ntwo\r\n")
        other.resolve("tracked.txt").writeText("one\ntwo\n")

        val comparison = compare(main, other, includeLocalChanges = false, ignoreLineEndings = false)

        assertEquals(listOf("tracked.txt"), comparison.entries.map { it.relativePath })
    }

    @Test
    fun `line ending only differences are hidden by text comparison`() = withRepository { main, other ->
        main.resolve("tracked.txt").writeText("one\r\ntwo\r\n")
        other.resolve("tracked.txt").writeText("one\ntwo\n")

        val comparison = compare(main, other, includeLocalChanges = false, ignoreLineEndings = true)

        assertTrue(comparison.entries.isEmpty())
    }

    @Test
    fun `content differences are still included by text comparison`() = withRepository { main, other ->
        main.resolve("tracked.txt").writeText("one\r\ntwo\r\n")
        other.resolve("tracked.txt").writeText("one\nthree\n")

        val comparison = compare(main, other, includeLocalChanges = false, ignoreLineEndings = true)

        assertEquals(listOf("tracked.txt"), comparison.entries.map { it.relativePath })
    }

    private fun withRepository(testBody: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("worktree-diff-test-")
        tempRoots.add(root)
        val main = root.resolve("main").createDirectories()
        val other = root.resolve("other")

        git(main, "init", "-b", "main")
        git(main, "config", "user.email", "test@example.com")
        git(main, "config", "user.name", "Test User")

        main.resolve(".gitignore").writeText("ignored.txt\n")
        main.resolve("tracked.txt").writeText("initial\n")
        git(main, "add", ".")
        git(main, "commit", "-m", "initial")
        git(main, "worktree", "add", "-b", "other", other.toString())
        git(other, "config", "user.email", "test@example.com")
        git(other, "config", "user.name", "Test User")

        testBody(main, other)
    }

    private fun compare(
        main: Path,
        other: Path,
        includeLocalChanges: Boolean,
        ignoreLineEndings: Boolean = false,
    ): ComparisonResult {
        val service = WorktreeService()
        val selected = service.listOtherWorktrees(main).single { it.path == other.toRealPath() }
        return WorktreeComparisonService().compare(main, selected, includeLocalChanges, ignoreLineEndings)
    }

    private fun git(workingDirectory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${arguments.joinToString(" ")} failed in $workingDirectory with $exitCode\n$output"
        }
        return output
    }
}
