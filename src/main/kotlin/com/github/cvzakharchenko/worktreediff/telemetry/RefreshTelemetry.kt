package com.github.cvzakharchenko.worktreediff.telemetry

import java.util.Locale

class RefreshTelemetry(
    private val operation: String,
) {
    private val startedAt = System.nanoTime()
    private val steps = mutableListOf<TimedStep>()
    private val metrics = linkedMapOf<String, String>()

    fun <T> measure(name: String, action: () -> T): T {
        val started = System.nanoTime()
        try {
            return action()
        } finally {
            addStep(name, System.nanoTime() - started)
        }
    }

    @Synchronized
    fun metric(name: String, value: Any?) {
        metrics[name] = value.toString()
    }

    fun summary(success: Boolean): String {
        val totalNanos = System.nanoTime() - startedAt
        val snapshot = snapshot()
        return buildString {
            append("Worktree Diff refresh timings: operation=")
            append(operation)
            append(" success=")
            append(success)
            snapshot.metrics.forEach { (name, value) ->
                append(' ')
                append(name)
                append('=')
                append(value)
            }
            append(" total=")
            append(totalNanos.toMillisString())
            snapshot.steps.forEach { step ->
                append(' ')
                append(step.name)
                append('=')
                append(step.nanos.toMillisString())
            }
        }
    }

    @Synchronized
    fun addStep(name: String, nanos: Long) {
        steps += TimedStep(name, nanos)
    }

    @Synchronized
    private fun snapshot(): Snapshot =
        Snapshot(steps.toList(), metrics.toMap())

    private data class Snapshot(
        val steps: List<TimedStep>,
        val metrics: Map<String, String>,
    )

    private data class TimedStep(
        val name: String,
        val nanos: Long,
    )
}

internal fun <T> RefreshTelemetry?.measure(name: String, action: () -> T): T =
    this?.measure(name, action) ?: action()

internal fun RefreshTelemetry?.metric(name: String, value: Any?) {
    this?.metric(name, value)
}

private fun Long.toMillisString(): String =
    String.format(Locale.US, "%.1fms", this / 1_000_000.0)
