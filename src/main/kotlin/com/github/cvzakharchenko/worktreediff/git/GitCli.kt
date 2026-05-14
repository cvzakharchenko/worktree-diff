package com.github.cvzakharchenko.worktreediff.git

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitCommandException(
    message: String,
    val command: List<String>,
    val workingDirectory: Path,
    val exitCode: Int? = null,
    val output: String = "",
) : RuntimeException(
    buildString {
        append(message)
        append(" in ")
        append(workingDirectory)
        append(": ")
        append(command.joinToString(" "))
        exitCode?.let {
            append(" exited with ")
            append(it)
        }
        if (output.isNotBlank()) {
            appendLine()
            append(output.trim())
        }
    },
)

class GitCli(
    private val executable: String = "git",
    private val timeout: Duration = Duration.ofSeconds(30),
) {

    fun runText(workingDirectory: Path, vararg arguments: String): String =
        runBytes(workingDirectory, *arguments).toString(StandardCharsets.UTF_8)

    fun runBytes(workingDirectory: Path, vararg arguments: String): ByteArray {
        val command = listOf(executable) + arguments
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .also {
                it.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            }
            .start()

        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.use { it.readBytes() }
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.use { it.readBytes() }
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw GitCommandException("Git command timed out", command, workingDirectory)
        }

        val stdoutBytes = stdout.get(5, TimeUnit.SECONDS)
        val stderrText = stderr.get(5, TimeUnit.SECONDS).toString(StandardCharsets.UTF_8)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw GitCommandException("Git command failed", command, workingDirectory, exitCode, stderrText)
        }

        return stdoutBytes
    }
}
