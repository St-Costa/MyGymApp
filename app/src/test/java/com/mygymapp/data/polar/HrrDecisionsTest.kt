package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure HRR/recovery decisions (ISM-11): [peakTrigger],
 * [settleHrrPeak], [hasRecovered] and [clampSampleMinutes]. Each row mirrors
 * one branch of the `PolarManager` capture path it was extracted from —
 * including the strict inequalities and the recovery/queue independence.
 */
class HrrDecisionsTest {

    // ─── Peak trigger ───

    private fun trigger(
        wasRising: Boolean = true,
        isRisingNow: Boolean = false,
        peakHr: Int = 150,
        restingHr: Int = 60,
        isRecovering: Boolean = false,
        meetsHrrThresholds: Boolean = true,
        nowMs: Long = 200_000L,
        lastQueuedAtMs: Long = 0L,
    ) = peakTrigger(
        wasRising, isRisingNow, peakHr, restingHr, isRecovering,
        meetsHrrThresholds, nowMs, lastQueuedAtMs,
        minRiseBpm = 15, debounceMs = 90_000L,
    )

    @Test
    fun `no peak without a rising-to-falling edge or without effort`() {
        assertEquals(PeakTrigger(false, false), trigger(wasRising = false))
        assertEquals(PeakTrigger(false, false), trigger(isRisingNow = true))
        // 70 vs rest 60: only +10, below the 15 BPM effort floor.
        assertEquals(PeakTrigger(false, false), trigger(peakHr = 70))
    }

    @Test
    fun `fresh peak latches recovery and queues HRR`() {
        assertEquals(PeakTrigger(true, true), trigger())
    }

    @Test
    fun `second peak while recovering still queues its own observation`() {
        // Recovery latch and HRR queue are independent — the mid-circuit case.
        assertEquals(
            PeakTrigger(startRecovery = false, queueHrr = true),
            trigger(isRecovering = true),
        )
    }

    @Test
    fun `queue needs stricter thresholds and a cooled-down debounce`() {
        assertEquals(
            PeakTrigger(startRecovery = true, queueHrr = false),
            trigger(meetsHrrThresholds = false),
        )
        assertEquals(
            PeakTrigger(startRecovery = true, queueHrr = false),
            trigger(nowMs = 90_000L, lastQueuedAtMs = 0L),
        )
        assertEquals(
            PeakTrigger(startRecovery = true, queueHrr = true),
            trigger(nowMs = 90_001L, lastQueuedAtMs = 0L),
        )
    }

    // ─── Settlement ───

    @Test
    fun `in-range monotonic delta is accepted`() {
        assertEquals(HrrSettlement.ACCEPT, settleHrrPeak(150, 120, monotonic = true))
        assertEquals(30, hrrDelta(150, 120))
    }

    @Test
    fun `in-range rebound is discarded`() {
        assertEquals(HrrSettlement.DISCARD_REBOUND, settleHrrPeak(150, 120, monotonic = false))
    }

    @Test
    fun `out-of-range delta is discarded without consulting monotonicity`() {
        assertEquals(HrrSettlement.DISCARD_OUT_OF_RANGE, settleHrrPeak(150, 200, monotonic = true))
        assertEquals(HrrSettlement.DISCARD_OUT_OF_RANGE, settleHrrPeak(150, 20, monotonic = true))
        // Boundaries 0 and 120 are accepted when monotonic.
        assertEquals(HrrSettlement.ACCEPT, settleHrrPeak(150, 150, monotonic = true))
        assertEquals(HrrSettlement.ACCEPT, settleHrrPeak(150, 30, monotonic = true))
    }

    // ─── Recovery exit ───

    private fun recovered(
        peak: Int = 150,
        rest: Int = 60,
        hr: Int = 80,
        rmssd: Double = 25.0,
        rrCount: Int = 20,
    ) = hasRecovered(peak, rest, hr, rmssd, rrCount, 0.70f, 20.0, 15)

    @Test
    fun `recovered when HR fell 70 percent and vagal tone is back`() {
        // (150-80)/(150-60) = 0.78 >= 0.70, rmssd 25 > 20, 20 >= 15.
        assertTrue(recovered())
    }

    @Test
    fun `not recovered when HR has not fallen enough`() {
        // (150-140)/90 = 0.11 < 0.70.
        assertFalse(recovered(hr = 140))
    }

    @Test
    fun `fraction boundary is inclusive, rmssd threshold is strict`() {
        // (150-87)/90 = 0.70 exactly -> ready; rmssd exactly 20 -> not ready.
        assertTrue(recovered(hr = 87, rmssd = 21.0))
        assertFalse(recovered(hr = 87, rmssd = 20.0))
        assertFalse(recovered(rrCount = 14))
    }

    @Test
    fun `peak never above rest vacates the HR condition`() {
        assertTrue(recovered(peak = 55, rest = 60, hr = 58, rmssd = 25.0))
    }

    // ─── Sample window ───

    @Test
    fun `only positive intervals within the gap window feed the accumulators`() {
        assertEquals(0.1, clampSampleMinutes(0.1))
        assertEquals(0.2, clampSampleMinutes(0.2))
        assertNull(clampSampleMinutes(0.0))
        assertNull(clampSampleMinutes(-0.5))
        assertNull(clampSampleMinutes(0.2001))
    }
}
