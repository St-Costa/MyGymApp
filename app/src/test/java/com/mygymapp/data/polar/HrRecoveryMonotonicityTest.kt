package com.mygymapp.data.polar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the hrr60s monotonicity/rebound check (see
 * [isMonotonicHrRecovery] and PolarManager.HRR_REBOUND_TOLERANCE_BPM).
 *
 * Server-side counterpart: MyGymApp_server/app/ecg_analysis.py `_hr_recovery_at`.
 */
class HrRecoveryMonotonicityTest {

    private val toleranceBpm = 8

    @Test
    fun `HR rebounds before the plus60s sample - delta is discarded`() {
        // Peak at 165, drops for 20s (genuine early recovery), then the user resumes
        // activity and HR climbs back up well past the tolerance before the +60s sample.
        val series = listOf(
            0L to 165,
            10_000L to 150,
            20_000L to 138,   // running min so far: 138
            30_000L to 145,   // +7 over min: still within an 8bpm tolerance
            40_000L to 160,   // +22 over min: rebound — activity resumed, not resting
            50_000L to 155,
            60_000L to 140,   // the "+60s" sample itself
        )

        val result = isMonotonicHrRecovery(
            series = series,
            peakHr = 165,
            peakElapsedMs = 0L,
            sampleElapsedMs = 60_000L,
            toleranceBpm = toleranceBpm,
        )

        assertFalse("A mid-window rebound past tolerance must discard the delta", result)
    }

    @Test
    fun `HR decreases monotonically - delta is included normally`() {
        // Peak at 165, drops steadily (small noise-level wobbles under tolerance) to 130
        // at +60s: a genuine passive recovery.
        val series = listOf(
            0L to 165,
            10_000L to 152,
            20_000L to 148,
            30_000L to 150,   // +2 over running min (148): well within tolerance, just noise
            40_000L to 140,
            50_000L to 134,
            60_000L to 130,
        )

        val result = isMonotonicHrRecovery(
            series = series,
            peakHr = 165,
            peakElapsedMs = 0L,
            sampleElapsedMs = 60_000L,
            toleranceBpm = toleranceBpm,
        )

        assertTrue("A monotonic (within-tolerance) decline must not be discarded", result)
    }

    @Test
    fun `samples outside the peak-to-sample window are ignored`() {
        // A huge spike well before the peak and well after the +60s sample must not affect
        // the verdict — only the window between peakElapsedMs and sampleElapsedMs counts.
        val series = listOf(
            -5_000L to 200,   // before the window: ignored
            0L to 165,
            30_000L to 145,
            60_000L to 130,
            90_000L to 210,   // after the window: ignored
        )

        val result = isMonotonicHrRecovery(
            series = series,
            peakHr = 165,
            peakElapsedMs = 0L,
            sampleElapsedMs = 60_000L,
            toleranceBpm = toleranceBpm,
        )

        assertTrue(result)
    }
}
