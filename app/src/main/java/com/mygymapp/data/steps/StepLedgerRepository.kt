package com.mygymapp.data.steps

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The raw `TYPE_STEP_COUNTER` value is cumulative since the device's last boot, so "steps
 * since the previous readiness test" requires remembering the counter value from that
 * previous test and subtracting. This repository is that one-row checkpoint
 * (`gymdata/_sync/step_checkpoint.yml`) — deliberately not folded into `ReadinessEvent`
 * itself, since a checkpoint is "the last raw counter reading" (a local computation aid,
 * never synced) while an event is "what got measured and synced" ([StepReading], produced by
 * [recordReadingAndComputeAverage] below).
 */
@Singleton
class StepLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun checkpointFile(): File = File(fileManager.getDir("_sync"), "step_checkpoint.yml")

    private data class Checkpoint(val counterValue: Long, val date: LocalDate)

    private fun readUnlocked(): Checkpoint? {
        val file = checkpointFile()
        if (!file.exists()) return null
        return try {
            val root = Load(loadSettings).loadFromString(file.readText()) as? Map<*, Any?>
                ?: return null
            val counterValue = (root["counterValue"] as? Number)?.toLong() ?: return null
            val date = (root["date"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return null
            Checkpoint(counterValue, date)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(checkpoint: Checkpoint) {
        checkpointFile().writeText(
            "counterValue: ${checkpoint.counterValue}\ndate: \"${checkpoint.date}\"\n"
        )
    }

    /**
     * Reads the current step counter, compares it against the last saved checkpoint, and
     * returns the average daily steps spanning however many days elapsed — then overwrites
     * the checkpoint with today's reading so the *next* call diffs from today, not from
     * whatever the gap was this time.
     *
     * [StepReading.daysSpanned] is carried alongside the average (rather than collapsing to
     * just the average) because it's data-quality information the server needs even though
     * the app only ever displays/stores the single averaged value locally: a "150 steps/day"
     * reading spanning 1 day and one spanning 5 skipped days are not equally trustworthy, and
     * only the server sees enough history to decide how to weight or flag the difference.
     *
     * Returns `null` (nothing to sync) when: no previous checkpoint exists yet (first ever
     * reading — nothing to diff against), the checkpoint is already dated today (readiness
     * only runs once/day, so this is defensive rather than expected), or the counter went
     * backwards (device rebooted between readings, which resets `TYPE_STEP_COUNTER` to 0 —
     * treated as missing data rather than produced as a bogus negative average).
     */
    suspend fun recordReadingAndComputeAverage(
        counterValue: Long,
        today: LocalDate = LocalDate.now(),
    ): StepReading? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = readUnlocked()
            writeUnlocked(Checkpoint(counterValue, today))
            diffAgainst(previous, counterValue, today)
        }
    }

    /**
     * Same diff as [recordReadingAndComputeAverage] but read-only — does not overwrite the
     * checkpoint. Used by the Options screen's step-counter debug button: pressing it must
     * never consume/shift the checkpoint the real readiness flow relies on, especially since
     * debug taps aren't restricted to once/day the way readiness is.
     */
    suspend fun peek(
        counterValue: Long,
        today: LocalDate = LocalDate.now(),
    ): StepReading? = withContext(Dispatchers.IO) {
        mutex.withLock { diffAgainst(readUnlocked(), counterValue, today) }
    }

    private fun diffAgainst(previous: Checkpoint?, counterValue: Long, today: LocalDate): StepReading? {
        if (previous == null) return null
        if (!previous.date.isBefore(today)) return null
        if (counterValue < previous.counterValue) return null

        val days = ChronoUnit.DAYS.between(previous.date, today).toInt().coerceAtLeast(1)
        val delta = counterValue - previous.counterValue
        return StepReading(avgStepsPerDay = delta.toDouble() / days, daysSpanned = days)
    }
}

/**
 * Result of diffing two step-counter checkpoints. [daysSpanned] is 1 on the common path (a
 * readiness test done every morning); anything greater means one or more days were skipped
 * and [avgStepsPerDay] is a multi-day average, not a true single-day count.
 */
data class StepReading(val avgStepsPerDay: Double, val daysSpanned: Int)
