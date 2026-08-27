package com.mygymapp.data.polar

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks how long the *current* CR2025 in the Polar H10 has been in active use, when it was
 * installed, and — accumulated across swaps — the average measured lifespan of a cell in this
 * setup. Surfaced next to the battery-percentage indicator on the connection screen as
 * `active hours / average measured lifespan`.
 *
 * Why this exists: the H10's reported percentage is derived from cell voltage, which stays
 * near 3V for most of a coin cell's life (see [PolarManager.BATTERY_WARNING_THRESHOLD] and
 * docs/CONVENTIONS.md). It gives no usable "hours remaining" estimate. Active hours since
 * install, measured against our own historical average, is a far more honest wear gauge.
 *
 * Everything is a local-only computation aid, never synced. Persisted as
 * `gymdata/_sync/battery_life.yml`.
 *
 * ## Automatic install detection
 *
 * There is no "I changed the battery" button. Instead [onBatteryLevel] watches the reported
 * percentage across readings: a rise of more than [JUMP_RESET_THRESHOLD_PCT] points versus the
 * last reading can only mean a fresh cell went in (a coin cell under load never recovers >5%
 * on its own). On that transition the *outgoing* cell's `activeSeconds` is appended to
 * [BatteryLifeState.pastLifeSeconds] (the history behind the average), then the install date
 * resets to today and the active-hours counter to zero.
 *
 * ## Active-time accumulation
 *
 * The H10 pushes a battery-level callback shortly after every connect and periodically while
 * connected. The elapsed wall-clock time between two consecutive callbacks is counted as
 * active use — unless the gap exceeds [MAX_SESSION_GAP_SEC], which means the strap was off
 * between them (so that span is not active use and is skipped, re-anchoring from the new
 * reading). This deliberately under-counts by at most one inter-callback interval per
 * session; it never over-counts.
 */
@Singleton
class BatteryLifeRepository @Inject constructor(
    private val fileManager: FileManager,
) {
    private val mutex = Mutex()
    private val loadSettings = LoadSettings.builder().build()

    private fun file(): File = File(fileManager.getDir("_sync"), "battery_life.yml")

    /**
     * Record a battery-level reading taken now. Returns the updated stats (never null once at
     * least one reading has been recorded). [nowEpochSec] and [today] are injectable for tests.
     */
    suspend fun onBatteryLevel(
        level: Int,
        nowEpochSec: Long = System.currentTimeMillis() / 1000,
        today: LocalDate = LocalDate.now(),
    ): BatteryLifeState = mutex.withLock {
        val next = reduce(readUnlocked(), level, nowEpochSec, today)
        writeUnlocked(next)
        next
    }

    /** Current persisted stats, or null if no reading has ever been recorded. */
    suspend fun peek(): BatteryLifeState? = mutex.withLock { readUnlocked() }

    private fun readUnlocked(): BatteryLifeState? {
        val f = file()
        if (!f.exists()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = Load(loadSettings).loadFromString(f.readText()) as? Map<String, Any?>
                ?: return null
            @Suppress("UNCHECKED_CAST")
            val past = (root["pastLifeSeconds"] as? List<Any?>).orEmptyLongs()
            BatteryLifeState(
                installedAtDate = LocalDate.parse(root["installedAtDate"] as String),
                installedAtLevel = (root["installedAtLevel"] as Number).toInt(),
                activeSeconds = (root["activeSeconds"] as Number).toLong(),
                lastLevel = (root["lastLevel"] as Number).toInt(),
                lastReadingEpochSec = (root["lastReadingEpochSec"] as Number).toLong(),
                pastLifeSeconds = past,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(s: BatteryLifeState) {
        file().writeText(
            buildString {
                append("installedAtDate: \"${s.installedAtDate}\"\n")
                append("installedAtLevel: ${s.installedAtLevel}\n")
                append("activeSeconds: ${s.activeSeconds}\n")
                append("lastLevel: ${s.lastLevel}\n")
                append("lastReadingEpochSec: ${s.lastReadingEpochSec}\n")
                if (s.pastLifeSeconds.isEmpty()) {
                    append("pastLifeSeconds: []\n")
                } else {
                    append("pastLifeSeconds:\n")
                    s.pastLifeSeconds.forEach { append("  - $it\n") }
                }
            },
        )
    }

    companion object {
        /** A reported rise larger than this (percentage points) versus the last reading is a swap. */
        const val JUMP_RESET_THRESHOLD_PCT = 5

        /** Gap between two readings beyond this (seconds) is not counted as active use. 30 min. */
        const val MAX_SESSION_GAP_SEC = 30L * 60L

        /**
         * A swap is only folded into the lifespan average if the outgoing cell logged at least
         * this much active use (2 h). Guards the average against a cell that was pulled and
         * reinserted, or swapped almost immediately after a previous swap, from dragging the
         * mean down with a near-zero "lifespan".
         */
        const val MIN_CREDIBLE_LIFE_SEC = 2L * 3600L

        /**
         * Pure state transition — all the logic, no I/O, so it is unit-tested directly.
         *
         * - No prior state (first reading ever): start fresh, install date = [today].
         * - `level - prev.lastLevel > JUMP_RESET_THRESHOLD_PCT`: battery was swapped. The
         *   outgoing cell's `activeSeconds` is appended to `pastLifeSeconds` (only if it clears
         *   [MIN_CREDIBLE_LIFE_SEC]), then install date / counter reset.
         * - Otherwise: accumulate the wall-clock gap since the last reading (capped by
         *   [MAX_SESSION_GAP_SEC]), and advance `lastLevel` / `lastReadingEpochSec`.
         */
        fun reduce(
            prev: BatteryLifeState?,
            level: Int,
            nowEpochSec: Long,
            today: LocalDate,
        ): BatteryLifeState {
            if (prev == null) {
                return BatteryLifeState(
                    installedAtDate = today,
                    installedAtLevel = level,
                    activeSeconds = 0L,
                    lastLevel = level,
                    lastReadingEpochSec = nowEpochSec,
                    pastLifeSeconds = emptyList(),
                )
            }
            if (level - prev.lastLevel > JUMP_RESET_THRESHOLD_PCT) {
                val history = if (prev.activeSeconds >= MIN_CREDIBLE_LIFE_SEC) {
                    prev.pastLifeSeconds + prev.activeSeconds
                } else {
                    prev.pastLifeSeconds
                }
                return BatteryLifeState(
                    installedAtDate = today,
                    installedAtLevel = level,
                    activeSeconds = 0L,
                    lastLevel = level,
                    lastReadingEpochSec = nowEpochSec,
                    pastLifeSeconds = history,
                )
            }
            val gap = nowEpochSec - prev.lastReadingEpochSec
            val add = if (gap in 1..MAX_SESSION_GAP_SEC) gap else 0L
            return prev.copy(
                activeSeconds = prev.activeSeconds + add,
                lastLevel = level,
                lastReadingEpochSec = nowEpochSec,
            )
        }

        private fun List<Any?>?.orEmptyLongs(): List<Long> =
            this?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()
    }
}

/**
 * Persisted state for CR2025 life tracking. [lastLevel] / [lastReadingEpochSec] are bookkeeping
 * for the next [BatteryLifeRepository.reduce] call; [installedAtDate] / [activeSeconds] describe
 * the cell in use now; [pastLifeSeconds] is the active-seconds each *previous* cell reached
 * before it was swapped out — the sample behind [avgLifeHours].
 */
data class BatteryLifeState(
    val installedAtDate: LocalDate,
    val installedAtLevel: Int,
    val activeSeconds: Long,
    val lastLevel: Int,
    val lastReadingEpochSec: Long,
    val pastLifeSeconds: List<Long> = emptyList(),
) {
    val activeHours: Double get() = activeSeconds / 3600.0

    fun daysSinceInstall(today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.DAYS.between(installedAtDate, today).toInt().coerceAtLeast(0)

    /** How many spent cells we have a measured lifespan for. */
    val measuredCellCount: Int get() = pastLifeSeconds.size

    /**
     * Mean measured lifespan of a cell, in hours — the average of every completed cell's
     * active-hours. `null` until at least one cell has been swapped out (nothing to average).
     */
    val avgLifeHours: Double?
        get() = pastLifeSeconds.takeIf { it.isNotEmpty() }
            ?.let { it.sum().toDouble() / it.size / 3600.0 }
}
