package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formula coverage for [SessionMetrics] — the pure kernels behind live session metrics
 * (cardiac drift, Keytel calories, Banister TRIMP, peak-detection rules). Extracted from
 * `PolarManager` precisely so these numbers are pinned by tests instead of only by
 * strap-on-chest manual runs. Reference: docs/polar/implementation-guide.md.
 */
class SessionMetricsTest {

    // ─── Cardiac drift ───

    @Test
    fun `drift needs data`() {
        assertEquals(0.0, SessionMetrics.driftSlopeBpmPerMinute(emptyList()), 0.0)
        val few = List(59) { i -> (i * 6000L) to 120 }
        assertEquals(0.0, SessionMetrics.driftSlopeBpmPerMinute(few), 0.0)
    }

    @Test
    fun `drift needs five minutes of span`() {
        // 60 samples but only 59 seconds covered.
        val short = List(60) { i -> (i * 1000L) to (100 + i) }
        assertEquals(0.0, SessionMetrics.driftSlopeBpmPerMinute(short), 0.0)
    }

    @Test
    fun `drift measures slope in bpm per minute`() {
        // +1 BPM every 30 s = exactly +2.0 BPM/min, all integer samples.
        val rising = List(120) { i -> (i * 30_000L) to 100 + i }
        assertEquals(2.0, SessionMetrics.driftSlopeBpmPerMinute(rising), 1e-9)
    }

    @Test
    fun `drift of flat series is zero, falling is negative`() {
        val flat = List(60) { i -> (i * 6000L) to 130 }
        assertEquals(0.0, SessionMetrics.driftSlopeBpmPerMinute(flat), 0.0)
        val falling = List(60) { i -> (i * 6000L) to 150 - i / 2 }
        assertTrue(SessionMetrics.driftSlopeBpmPerMinute(falling) < 0.0)
    }

    // ─── Keytel calories ───

    @Test
    fun `keytel male golden value`() {
        // (-55.0969 + 0.6309*150 + 0.1988*80 + 0.2017*30) / 4.184
        // = 61.4931 / 4.184 ≈ 14.697
        assertEquals(
            14.697,
            SessionMetrics.keytelKcalPerMinute(hr = 150, weightKg = 80.0, ageYears = 30, isMale = true),
            1e-3,
        )
    }

    @Test
    fun `keytel female golden value`() {
        // (-20.4022 + 0.4472*140 - 0.1263*65 + 0.074*30) / 4.184
        // = 36.2163 / 4.184 ≈ 8.656
        assertEquals(
            8.656,
            SessionMetrics.keytelKcalPerMinute(hr = 140, weightKg = 65.0, ageYears = 30, isMale = false),
            1e-3,
        )
    }

    @Test
    fun `keytel rises with hr and weight`() {
        val base = SessionMetrics.keytelKcalPerMinute(120, 70.0, 30, true)
        assertTrue(SessionMetrics.keytelKcalPerMinute(150, 70.0, 30, true) > base)
        assertTrue(SessionMetrics.keytelKcalPerMinute(120, 90.0, 30, true) > base)
    }

    // ─── Banister TRIMP ───

    @Test
    fun `trimp golden value at 50 percent HRR`() {
        // hrr = (125-60)/(190-60) = 0.5 → 0.5 * 0.64 * exp(1.92*0.5) ≈ 0.8357
        assertEquals(
            0.8357,
            SessionMetrics.banisterTrimpPerMinute(hr = 125, restingHr = 60, hrMax = 190, isMale = true),
            1e-4,
        )
    }

    @Test
    fun `trimp is zero at and below rest`() {
        assertEquals(0.0, SessionMetrics.banisterTrimpPerMinute(60, 60, 190, true), 0.0)
        assertEquals(0.0, SessionMetrics.banisterTrimpPerMinute(50, 60, 190, true), 0.0)
    }

    @Test
    fun `trimp female weighting differs from male`() {
        val male = SessionMetrics.banisterTrimpPerMinute(150, 60, 190, true)
        val female = SessionMetrics.banisterTrimpPerMinute(150, 60, 190, false)
        assertTrue(male > 0 && female > 0 && male != female)
    }

    // ─── Peak-detection rules ───

    @Test
    fun `rising window detected, falling and flat are not`() {
        assertTrue(SessionMetrics.isRisingHalf(listOf(100, 101, 102, 103, 110, 111, 112, 113)))
        assertFalse(SessionMetrics.isRisingHalf(listOf(113, 112, 111, 110, 103, 102, 101, 100)))
        assertFalse(SessionMetrics.isRisingHalf(List(8) { 100 }))
    }

    @Test
    fun `tiny windows say nothing`() {
        assertFalse(SessionMetrics.isRisingHalf(emptyList()))
        assertFalse(SessionMetrics.isRisingHalf(listOf(100, 120, 140)))
    }

    @Test
    fun `hrr admission needs rise and height`() {
        // rise 90 ≥ 25, 150 ≥ 0.6 * 190 = 114
        assertTrue(SessionMetrics.peakMeetsHrrThresholds(peakHr = 150, restingHr = 60, hrMax = 190))
        // enough rise but too low vs max
        assertFalse(SessionMetrics.peakMeetsHrrThresholds(peakHr = 100, restingHr = 60, hrMax = 190))
        // high vs max but no real rise
        assertFalse(SessionMetrics.peakMeetsHrrThresholds(peakHr = 120, restingHr = 110, hrMax = 190))
    }
}
