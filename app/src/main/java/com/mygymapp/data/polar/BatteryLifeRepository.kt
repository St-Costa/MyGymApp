package com.mygymapp.data.polar

import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.util.AtomicFiles
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
 * ## Install detection is manual only
 *
 * A cell swap is recorded only when the user confirms it from the HR screen's battery
 * indicator ("Hai cambiato la batteria?") via [resetForNewBattery]: the *outgoing* cell's
 * `activeSeconds` is appended to [BatteryLifeState.pastLifeSeconds] (the history behind the
 * average), then the install date resets to today and the active-hours counter to zero.
 * Battery readings ([onBatteryLevel]) never reset anything on their own — the old ">5% rise
 * = new cell" auto-detection was dropped (Phase 192): the percentage is a flat voltage proxy,
 * so it both missed real swaps (~100% before and after) and could fire on a noisy reading.
 *
 * ## Active-time accumulation
 *
 * Active use is *connected* time, not battery-callback gaps. The H10 pushes a battery
 * level essentially once per connect (the voltage-derived percentage barely moves, so the
 * SDK has nothing new to report mid-session) — accumulating "time between consecutive
 * callbacks" therefore measured ~0 h forever: every inter-session gap exceeded the old
 * 30-minute cap and was discarded. Instead [PolarManager] anchors a timestamp on every
 * BLE connect, adds the elapsed span on every disconnect, and checkpoints it every
 * [PolarManager.BATTERY_HEARTBEAT_MS] while connected via [addActiveSeconds] (so a
 * process death loses at most one heartbeat interval, and the tail of a session is never
 * lost). Connected radio-on time is the honest wear gauge for a coin cell, regardless of
 * whether HR/ECG streams were flowing.
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

    /**
     * Add a connected-time span (seconds) to the current cell's counter. Called by
     * [PolarManager] on disconnect and on every in-connection heartbeat. Non-positive spans
     * are ignored (clock skew); the caller caps abnormally large spans (missed heartbeats,
     * forward clock jumps) before calling. Returns null when no cell has ever been seeded
     * by [onBatteryLevel] — with no install baseline there is nothing to attribute the
     * span to, so it is dropped rather than inventing a cell.
     */
    suspend fun addActiveSeconds(seconds: Long): BatteryLifeState? = mutex.withLock {
        if (seconds <= 0) return readUnlocked()
        val prev = readUnlocked() ?: return null
        val next = prev.copy(activeSeconds = prev.activeSeconds + seconds)
        writeUnlocked(next)
        next
    }

    /**
     * Manual "I changed the CR2025" reset — the only way a cell swap is recorded. The
     * outgoing cell's hours join the lifespan history only if credible
     * ([MIN_CREDIBLE_LIFE_SEC]), then install date / counter restart. With no prior state
     * and a known [currentLevel] this seeds the first cell instead of failing.
     */
    suspend fun resetForNewBattery(
        currentLevel: Int?,
        nowEpochSec: Long = System.currentTimeMillis() / 1000,
        today: LocalDate = LocalDate.now(),
    ): BatteryLifeState? = mutex.withLock {
        val prev = readUnlocked()
        if (prev == null) {
            val level = currentLevel ?: return null
            val next = BatteryLifeState(
                installedAtDate = today,
                installedAtLevel = level,
                activeSeconds = 0L,
                lastLevel = level,
                lastReadingEpochSec = nowEpochSec,
                pastLifeSeconds = emptyList(),
            )
            writeUnlocked(next)
            return next
        }
        val level = currentLevel ?: prev.lastLevel
        val history = if (prev.activeSeconds >= MIN_CREDIBLE_LIFE_SEC) {
            prev.pastLifeSeconds + prev.activeSeconds
        } else {
            prev.pastLifeSeconds
        }
        val next = BatteryLifeState(
            installedAtDate = today,
            installedAtLevel = level,
            activeSeconds = 0L,
            lastLevel = level,
            lastReadingEpochSec = nowEpochSec,
            pastLifeSeconds = history,
        )
        writeUnlocked(next)
        next
    }

    private fun readUnlocked(): BatteryLifeState? {
        val f = file()
        if (!f.exists()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = Load(loadSettings).loadFromString(f.readText()) as? Map<String, Any?>
                ?: return null
            @Suppress("UNCHECKED_CAST")
            val past = (root["pastLifeSeconds"] as? List<Any?>).orEmptyLongs()
            // Safe casts throughout: a hand-edited YAML with a wrong type used
            // to throw ClassCastException/DateTimeParseException here, caught
            // below into a silent `null` that wiped the battery stats. Now a
            // wrong-typed field returns null only for that read, same outcome
            // but without the exception round-trip — and the write path below
            // never persists a partial state over a good file.
            BatteryLifeState(
                installedAtDate = (root["installedAtDate"] as? String)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return null,
                installedAtLevel = (root["installedAtLevel"] as? Number)?.toInt() ?: return null,
                activeSeconds = (root["activeSeconds"] as? Number)?.toLong() ?: return null,
                lastLevel = (root["lastLevel"] as? Number)?.toInt() ?: return null,
                lastReadingEpochSec = (root["lastReadingEpochSec"] as? Number)?.toLong() ?: return null,
                pastLifeSeconds = past,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(s: BatteryLifeState) {
        AtomicFiles.writeTextAtomically(file(),
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
        /**
         * A swap is only folded into the lifespan average if the outgoing cell logged at least
         * this much active use (2 h). Guards the average against a cell that was pulled and
         * reinserted, or swapped almost immediately after a previous swap, from dragging the
         * mean down with a near-zero "lifespan".
         */
        const val MIN_CREDIBLE_LIFE_SEC = 2L * 3600L

        /**
         * Pure state transition for a battery-level reading — bookkeeping only, no I/O, so it
         * is unit-tested directly.
         *
         * - No prior state (first reading ever): seed the first cell, install date = [today].
         * - Otherwise: advance `lastLevel` / `lastReadingEpochSec`, nothing else. A reading
         *   never resets the cell, however much the level rises — swaps are manual
         *   ([resetForNewBattery]). `activeSeconds` is untouched too (spans arrive via
         *   [addActiveSeconds]; battery callbacks come ~once per connect).
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
            return prev.copy(
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
