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
    fun `consecutive readings only advance bookkeeping, never active time`() {
        var s = reduce(null, 90, nowEpochSec = 0)
        s = reduce(s, 90, nowEpochSec = 600)
        s = reduce(s, 89, nowEpochSec = 1_200)
        // Spans are added via addActiveSeconds (connect/disconnect/heartbeat), not here:
        // battery callbacks arrive ~once per connect, so gap counting measured 0 h forever.
        assertEquals(0L, s.activeSeconds)
        assertEquals(89, s.lastLevel)
        assertEquals(1_200L, s.lastReadingEpochSec)
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
    fun `a rise in level never resets the cell, swaps are manual only`() {
        var s = reduce(null, 70, nowEpochSec = 0)
        s = s.copy(activeSeconds = 10 * 3600L) // as addActiveSeconds would have recorded

        // Next day: 70% -> 100%. Used to be auto-detected as a swap; now bookkeeping only.
        s = reduce(s, 100, nowEpochSec = 700, today = day2)
        assertEquals(day1, s.installedAtDate)
        assertEquals(70, s.installedAtLevel)
        assertEquals(10 * 3600L, s.activeSeconds)
        assertEquals(100, s.lastLevel)
        assertEquals(700L, s.lastReadingEpochSec)
        assertTrue(s.pastLifeSeconds.isEmpty())
    }

    private val hour = 3600L

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
