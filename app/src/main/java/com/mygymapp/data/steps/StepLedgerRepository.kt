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
     * Fetches steps from [reader] since the last saved checkpoint, then advances the
     * checkpoint to `now` so the *next* call picks up from here.
     *
     * On the very first call ever (no checkpoint saved yet — e.g. the day the user first
     * grants the permission), there's no "previous" instant to diff against, but Health
     * Connect still has real historical data (steps tracked before this app ever had
     * permission to read them) — so instead of returning `null` and making day one look
     * broken, this queries the last 24h directly ([FIRST_READ_LOOKBACK]) as a real time
     * range rather than a diff. Every read after that goes back to normal checkpoint-diffing.
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
            fetchAndAverage(reader, previous ?: now.minus(FIRST_READ_LOOKBACK), now)
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
        mutex.withLock {
            val previous = readUnlocked()
            fetchAndAverage(reader, previous ?: now.minus(FIRST_READ_LOOKBACK), now)
        }
    }

    private suspend fun fetchAndAverage(reader: HealthConnectStepsReader, since: Instant, now: Instant): StepReading? {
        if (!since.isBefore(now)) return null
        val total = reader.totalSteps(since, now) ?: return null
        val days = ChronoUnit.DAYS.between(since, now).toInt().coerceAtLeast(1)
        return StepReading(
            avgStepsPerDay = total.toDouble() / days,
            daysSpanned = days,
            previousDayTotal = reader.previousDayTotal(now),
        )
    }

    companion object {
        private val FIRST_READ_LOOKBACK = java.time.Duration.ofHours(24)
    }
}

/**
 * Result of aggregating steps since the last checkpoint. [daysSpanned] is 1 on the common
 * path (a readiness test done every morning); anything greater means one or more days were
 * skipped and [avgStepsPerDay] is a multi-day average, not a true single-day count.
 */
data class StepReading(
    val avgStepsPerDay: Double,
    val daysSpanned: Int,
    /**
     * Yesterday's complete calendar-day step total (local midnight to midnight), read
     * straight from Health Connect and independent of the checkpoint diff above — this is
     * what the readiness box shows, because "ieri hai fatto N passi" is a real, whole,
     * comparable number, while the checkpoint average shifts with whatever time of day the
     * readiness test happened to be taken. `null` when Health Connect couldn't answer.
     */
    val previousDayTotal: Long? = null,
)
