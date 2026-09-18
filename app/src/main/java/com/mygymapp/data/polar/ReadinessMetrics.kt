package com.mygymapp.data.polar

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure computation kernels behind the morning HRV readiness measurement. Everything here
 * is a deterministic function of its inputs — no clock, no BLE, no disk — so the math
 * is unit-testable without a strap (see `ReadinessMetricsTest`). The stateful
 * orchestration (60 s sample collection, baseline lookup, persist + sync) stays in
 * [PolarManager].
 *
 * The rolling baseline + classification live one layer up in [HrvBaselineCalculator]
 * (already extracted); the VO2max window, unlike the baseline, needs no history.
 */
object ReadinessMetrics {

    /**
     * Drops implausible RR intervals: outside 300..2000 ms, or deviating ≥20% from the
     * median of the in-range samples. Passes through untouched when fewer than 3
     * in-range samples exist (nothing to take a median of).
     */
    fun filterArtifacts(rrIntervals: List<Int>): List<Int> {
        val filtered = rrIntervals.filter { it in 300..2000 }
        if (filtered.size < 3) return filtered
        val sorted = filtered.sorted()
        val median = sorted[sorted.size / 2]
        return filtered.filter { abs(it - median) < median * 0.20 }
    }

    /** Root mean square of successive RR differences. Zero for fewer than 2 samples. */
    fun calculateRMSSD(rrIntervals: List<Int>): Double {
        if (rrIntervals.size < 2) return 0.0
        val diffs = rrIntervals.zipWithNext { a, b -> (b - a).toDouble().pow(2) }
        return sqrt(diffs.average())
    }

    /**
     * Uth et al. VO2max estimate from max vs resting HR. Null when [restingHr] is not
     * positive — the caller shows "n/a" rather than a bogus number.
     */
    fun uthVo2max(hrMax: Int, restingHr: Int): Double? =
        if (restingHr > 0) 15.3 * (hrMax.toDouble() / restingHr) else null
}
