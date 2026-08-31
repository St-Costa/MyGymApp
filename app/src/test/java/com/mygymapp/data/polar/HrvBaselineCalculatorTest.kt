package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [HrvBaselineCalculator] — the Phase 94 replacement for the wiped-on-restore
 * `SharedPreferences("hrv_baseline")` mirror. The z-score thresholds and rolling-window
 * sizes must stay identical to the old inline `PolarManager` logic.
 */
class HrvBaselineCalculatorTest {

    // --- lnRmssdBaseline: trailing window, drops invalid ---------------------------------

    @Test
    fun `lnRmssdBaseline keeps only the trailing 14 valid samples`() {
        val history = (1..20).map { it.toDouble() } // 1.0 .. 20.0
        val baseline = HrvBaselineCalculator.lnRmssdBaseline(history)
        assertEquals(14, baseline.size)
        assertEquals(7.0, baseline.first(), 1e-9)  // 20 - 14 + 1
        assertEquals(20.0, baseline.last(), 1e-9)
    }

    @Test
    fun `lnRmssdBaseline filters non-positive values before trimming`() {
        val history = listOf(4.0, 0.0, 4.2, -1.0, 4.1)
        assertEquals(listOf(4.0, 4.2, 4.1), HrvBaselineCalculator.lnRmssdBaseline(history))
    }

    // --- hrRestBaseline: includes today, trailing 7 -------------------------------------

    @Test
    fun `hrRestBaseline keeps trailing 7 including today`() {
        val recentInclToday = listOf(60, 61, 62, 63, 64, 65, 66, 67, 58)
        val baseline = HrvBaselineCalculator.hrRestBaseline(recentInclToday)
        assertEquals(listOf(62, 63, 64, 65, 66, 67, 58), baseline)
        assertEquals(58, baseline.minOrNull())
    }

    @Test
    fun `hrRestBaseline drops zero readings`() {
        assertEquals(listOf(60, 58), HrvBaselineCalculator.hrRestBaseline(listOf(60, 0, 58)))
    }

    // --- classify: still-collecting path ----------------------------------------------

    @Test
    fun `classify with fewer than 7 prior samples reports NO_BASELINE and a collecting count`() {
        val (readiness, rec) = HrvBaselineCalculator.classify(3.7, listOf(4.0, 4.1, 4.2))
        assertEquals(Readiness.NO_BASELINE, readiness)
        // 3 prior -> "(4/7 days)"
        assertTrue("recommendation was: $rec", rec.contains("(4/7 days)"))
    }

    @Test
    fun `classify at exactly 7 samples computes a z-score instead of collecting`() {
        val baseline = List(7) { 4.0 }  // sd == 0 -> zScore 0 -> NORMAL
        val (readiness, _) = HrvBaselineCalculator.classify(4.0, baseline)
        assertEquals(Readiness.NORMAL, readiness)
    }

    // --- classify: z-score buckets (thresholds frozen from pre-Phase-94) ---------------

    private fun baselineWith(mean: Double, sd: Double): List<Double> {
        // Two points at mean±sd give population mean=mean, population sd=sd, n>=7 via repeats.
        return List(4) { mean - sd } + List(4) { mean + sd }
    }

    @Test
    fun `classify buckets by z-score`() {
        val b = baselineWith(mean = 4.0, sd = 0.2)

        // z = -2.0  -> DELOAD
        assertEquals(Readiness.DELOAD_RECOMMENDED, HrvBaselineCalculator.classify(3.6, b).readiness)
        // z = -1.25 -> LIGHT_DAY
        assertEquals(Readiness.LIGHT_DAY, HrvBaselineCalculator.classify(3.75, b).readiness)
        // z = 0     -> NORMAL
        assertEquals(Readiness.NORMAL, HrvBaselineCalculator.classify(4.0, b).readiness)
        // z = 1.25  -> GOOD
        assertEquals(Readiness.GOOD, HrvBaselineCalculator.classify(4.25, b).readiness)
        // z = 2.0   -> PEAK
        assertEquals(Readiness.PEAK, HrvBaselineCalculator.classify(4.4, b).readiness)
    }

    @Test
    fun `classify boundary at z equals minus 1 is NORMAL not LIGHT_DAY`() {
        val b = baselineWith(mean = 4.0, sd = 0.2)
        // z exactly -1.0: `zScore < -1.0` is false -> NORMAL
        assertEquals(Readiness.NORMAL, HrvBaselineCalculator.classify(3.8, b).readiness)
    }
}
