package com.mygymapp.data.steps

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only checkpoint (`gymdata/_sync/step_checkpoint.yml`) recording *when* steps were
 * last read from Health Connect — never synced, purely a computation aid. Simpler than the
 * original raw-sensor version of this class: Health Connect's [HealthConnectStepsReader]
 * aggregates over an explicit time range itself, so this repository only needs to remember
 * "since when", not a cumulative counter value to diff.
 */
@Singleton
class StepLedgerRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun checkpointFile(): File = File(fileManager.getDir("_sync"), "step_checkpoint.yml")

    private fun readUnlocked(): Instant? {
        val file = checkpointFile()
        if (!file.exists()) return null
        return try {
            val root = Load(loadSettings).loadFromString(file.readText()) as? Map<*, Any?>
                ?: return null
            (root["lastReadAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(at: Instant) {
        checkpointFile().writeText("lastReadAt: \"$at\"\n")
    }

    /**
     * Fetches steps from [reader] since the last saved checkpoint (or does nothing and
     * returns `null` if this is the very first call ever — nothing to bound the query's
     * start against), then advances the checkpoint to `now` so the *next* call picks up
     * from here.
     *
     * [StepReading.daysSpanned] mirrors the original raw-sensor design's reasoning (see
     * SYNC.md § Daily step average): if a day was skipped, the total gets divided across
     * however many days actually elapsed rather than reported as if it were one day's count.
     */
    suspend fun recordReadingAndComputeAverage(
        reader: HealthConnectStepsReader,
        now: Instant = Instant.now(),
    ): StepReading? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = readUnlocked()
            writeUnlocked(now)
            previous?.let { fetchAndAverage(reader, it, now) }
        }
    }

    /**
     * Same fetch as [recordReadingAndComputeAverage] but read-only — does not advance the
     * checkpoint. Used by the Options screen's step-counter debug button: pressing it must
     * never itself consume/shift the checkpoint the real readiness flow relies on.
     */
    suspend fun peek(
        reader: HealthConnectStepsReader,
        now: Instant = Instant.now(),
    ): StepReading? = withContext(Dispatchers.IO) {
        mutex.withLock { readUnlocked()?.let { fetchAndAverage(reader, it, now) } }
    }

    private suspend fun fetchAndAverage(reader: HealthConnectStepsReader, since: Instant, now: Instant): StepReading? {
        if (!since.isBefore(now)) return null
        val total = reader.totalSteps(since, now) ?: return null
        val days = ChronoUnit.DAYS.between(since, now).toInt().coerceAtLeast(1)
        return StepReading(avgStepsPerDay = total.toDouble() / days, daysSpanned = days)
    }
}

/**
 * Result of aggregating steps since the last checkpoint. [daysSpanned] is 1 on the common
 * path (a readiness test done every morning); anything greater means one or more days were
 * skipped and [avgStepsPerDay] is a multi-day average, not a true single-day count.
 */
data class StepReading(val avgStepsPerDay: Double, val daysSpanned: Int)
