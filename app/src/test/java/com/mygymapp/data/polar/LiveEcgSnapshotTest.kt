package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 5: `regularPct` is NaN — never a fake 100% — until the detector has seen a beat.
 * (The card renders "—" at 0 beats and withholds the semaphore verdict below 10 beats.)
 */
class LiveEcgSnapshotTest {

    @Test
    fun `fresh analyzer reports zero beats and NaN regularPct`() {
        val snapshot = LiveEcgAnalyzer().snapshot()

        assertEquals(0, snapshot.beats)
        assertTrue(snapshot.regularPct.isNaN())
        assertEquals(0, snapshot.irregularities)
    }

    @Test
    fun `reset returns to zero beats and NaN regularPct`() {
        val analyzer = LiveEcgAnalyzer()
        repeat(500) { analyzer.onSample(0) }
        analyzer.reset()

        val snapshot = analyzer.snapshot()

        assertEquals(0, snapshot.beats)
        assertTrue(snapshot.regularPct.isNaN())
    }
}
