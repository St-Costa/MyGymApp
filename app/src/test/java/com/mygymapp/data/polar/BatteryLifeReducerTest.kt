package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Coverage for [BatteryLifeRepository.reduce] — the pure state transition behind the H10
 * active-hours / install-date tracker shown next to the battery percentage. File I/O in the
 * repository is a thin wrapper around this; all the behaviour worth testing is here.
 */
class BatteryLifeReducerTest {

    private val day1 = LocalDate.of(2026, 6, 10)
    private val day2 = LocalDate.of(2026, 6, 11)

    private fun reduce(
        prev: BatteryLifeState?,
        level: Int,
        nowEpochSec: Long,
        today: LocalDate = day1,
    ) = BatteryLifeRepository.reduce(prev, level, nowEpochSec, today)

    @Test
    fun `first reading ever seeds install date and zero active time`() {
        val s = reduce(prev = null, level = 100, nowEpochSec = 1_000, today = day1)
        assertEquals(day1, s.installedAtDate)
        assertEquals(100, s.installedAtLevel)
        assertEquals(0L, s.activeSeconds)
        assertEquals(100, s.lastLevel)
        assertEquals(1_000L, s.lastReadingEpochSec)
    }

    @Test
    fun `consecutive readings within the gap cap accumulate active seconds`() {
        var s = reduce(null, 90, nowEpochSec = 0)
        s = reduce(s, 90, nowEpochSec = 600)   // +10 min
        s = reduce(s, 89, nowEpochSec = 1_200) // +10 min
        assertEquals(1_200L, s.activeSeconds)
        assertEquals(89, s.lastLevel)
    }

    @Test
    fun `a gap longer than the session cap is not counted as active use`() {
        var s = reduce(null, 90, nowEpochSec = 0)
        // 3 hours later — strap was off in between, so this span is skipped.
        s = reduce(s, 88, nowEpochSec = 3 * 3600)
        assertEquals(0L, s.activeSeconds)
        assertEquals(88, s.lastLevel)
        assertEquals(3 * 3600L, s.lastReadingEpochSec) // re-anchored for next time
    }

    @Test
    fun `a rise of more than five points resets install date and counter`() {
        var s = reduce(null, 70, nowEpochSec = 0)
        s = reduce(s, 70, nowEpochSec = 600) // 600s of use on the old cell
        assertEquals(600L, s.activeSeconds)

        // Next day: 70% -> 100%. Fresh CR2025.
        s = reduce(s, 100, nowEpochSec = 700, today = day2)
        assertEquals(day2, s.installedAtDate)
        assertEquals(100, s.installedAtLevel)
        assertEquals(0L, s.activeSeconds)
        assertEquals(100, s.lastLevel)
    }

    private val hour = 3600L

    @Test
    fun `a swap folds the outgoing cell's active hours into the lifespan history`() {
        var s = reduce(null, 100, nowEpochSec = 0)
        // 40 h of active use accumulated in <=30min steps.
        repeat(80) { i -> s = reduce(s, 100 - i / 8, nowEpochSec = (i + 1) * 30 * 60L) }
        assertEquals(40.0, s.activeHours, 0.01)
        assertTrue(s.pastLifeSeconds.isEmpty())
        assertNull(s.avgLifeHours)

        // Swap: reported level jumps back up.
        val afterSwap = reduce(s, 100, nowEpochSec = s.lastReadingEpochSec + 60, today = day2)
        assertEquals(listOf(40 * hour), afterSwap.pastLifeSeconds)
        assertEquals(40.0, afterSwap.avgLifeHours!!, 0.01)
        assertEquals(0L, afterSwap.activeSeconds)
        assertEquals(1, afterSwap.measuredCellCount)
    }

    @Test
    fun `avgLifeHours is the mean of every past cell`() {
        val s = BatteryLifeState(
            installedAtDate = day1, installedAtLevel = 100, activeSeconds = 5 * hour,
            lastLevel = 80, lastReadingEpochSec = 0,
            pastLifeSeconds = listOf(30 * hour, 50 * hour, 40 * hour),
        )
        assertEquals(40.0, s.avgLifeHours!!, 1e-9) // (30+50+40)/3
        assertEquals(3, s.measuredCellCount)
    }

    @Test
    fun `a near-zero-life swap is not credited to the average`() {
        var s = reduce(null, 100, nowEpochSec = 0)
        s = reduce(s, 99, nowEpochSec = hour) // only 1 h on this cell — below MIN_CREDIBLE_LIFE_SEC
        s = reduce(s, 100, nowEpochSec = hour + 60, today = day2) // pulled & reinserted
        assertTrue(s.pastLifeSeconds.isEmpty())
        assertNull(s.avgLifeHours)
    }

    @Test
    fun `history is preserved across a later swap`() {
        var s = BatteryLifeState(
            installedAtDate = day1, installedAtLevel = 100, activeSeconds = 20 * hour,
            lastLevel = 75, lastReadingEpochSec = 1_000_000,
            pastLifeSeconds = listOf(45 * hour),
        )
        s = reduce(s, 100, nowEpochSec = 1_000_060, today = day2)
        assertEquals(listOf(45 * hour, 20 * hour), s.pastLifeSeconds)
        assertEquals(32.5, s.avgLifeHours!!, 1e-9)
    }

    @Test
    fun `a rise of exactly five points is treated as noise, not a swap`() {
        var s = reduce(null, 80, nowEpochSec = 0)
        s = reduce(s, 85, nowEpochSec = 600) // +5 exactly -> not a reset
        assertEquals(day1, s.installedAtDate)
        assertEquals(600L, s.activeSeconds)
        assertEquals(85, s.lastLevel)
    }

    @Test
    fun `a small rise from a swap of a partially-used cell still resets`() {
        // Old cell at 40%, new cell inserted also not full, reports 60% -> +20 > 5.
        var s = reduce(null, 40, nowEpochSec = 0)
        s = reduce(s, 60, nowEpochSec = 600, today = day2)
        assertEquals(day2, s.installedAtDate)
        assertEquals(60, s.installedAtLevel)
        assertEquals(0L, s.activeSeconds)
    }

    @Test
    fun `activeHours and daysSinceInstall derive from state`() {
        val s = BatteryLifeState(
            installedAtDate = day1,
            installedAtLevel = 100,
            activeSeconds = 3600 * 9 + 1800, // 9.5 h
            lastLevel = 82,
            lastReadingEpochSec = 0,
        )
        assertEquals(9.5, s.activeHours, 1e-9)
        assertEquals(5, s.daysSinceInstall(LocalDate.of(2026, 6, 15)))
        assertEquals(0, s.daysSinceInstall(day1))
        // Clock skew / earlier date never goes negative.
        assertEquals(0, s.daysSinceInstall(LocalDate.of(2026, 6, 1)))
        // No swaps yet -> no average.
        assertNull(s.avgLifeHours)
        assertEquals(0, s.measuredCellCount)
    }
}
