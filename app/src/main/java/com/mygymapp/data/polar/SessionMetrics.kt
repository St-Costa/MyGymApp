package com.mygymapp.data.polar

import kotlin.math.exp

/**
 * Pure computation kernels behind a workout session's live metrics. Everything here is a
 * deterministic function of its inputs — no clock, no BLE, no StateFlow — so the formulas
 * are unit-testable without a strap (see `SessionMetricsTest`). The stateful orchestration
 * (when to feed samples, where the results land) stays in [PolarManager].
 *
 * Reference for the calorie/TRIMP formulas: docs/polar/implementation-guide.md.
 */
object SessionMetrics {

    // HRR peak thresholds — stricter than the recovery-semaphore ones, to avoid false
    // positives from light activity (walking, stair climbing). Single source of truth;
    // PolarManager's peak detection reads these.
    const val HRR_PEAK_MIN_RISE_BPM = 25
    const val HRR_PEAK_MIN_HRMAX_FRACTION = 0.6f

    /**
     * Cardiac drift rate in BPM/min over captured `(elapsedMs, hr)` samples.
     * Requires ≥60 samples spanning ≥5 minutes, otherwise returns 0.
     * Positive = HR drifted upward (possible dehydration/heat).
     * Linear-regression slope of HR vs minutes.
     */
    fun driftSlopeBpmPerMinute(points: List<Pair<Long, Int>>): Double {
        if (points.size < 60) return 0.0
        val totalMinutes = points.last().first / 60000.0
        if (totalMinutes < 5.0) return 0.0
        val xs = points.map { it.first / 60000.0 }
        val ys = points.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            num += dx * (ys[i] - meanY)
            den += dx * dx
        }
        return if (den > 0) num / den else 0.0
    }

    /**
     * Keytel et al. (2005) energy expenditure in kcal/min for one HR sample.
     * May be ≤ 0 for implausible inputs — the caller only accumulates positive values.
     */
    fun keytelKcalPerMinute(hr: Int, weightKg: Double, ageYears: Int, isMale: Boolean): Double =
        if (isMale) {
            (-55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * ageYears) / 4.184
        } else {
            (-20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * ageYears) / 4.184
        }

    /**
     * Banister TRIMP accumulated over one minute at [hr]: duration × HRR fraction ×
     * exponential weighting. Multiply by the actual elapsed minutes. Zero at/below rest.
     */
    fun banisterTrimpPerMinute(hr: Int, restingHr: Int, hrMax: Int, isMale: Boolean): Double {
        val hrr = (hr - restingHr).toDouble() / (hrMax - restingHr)
        val clampedHrr = hrr.coerceIn(0.0, 1.0)
        val genderExp = if (isMale) 1.92 else 1.67
        return clampedHrr * 0.64 * exp(genderExp * clampedHrr)
    }

    /**
     * Peak-detector rising rule: the window's second half averages >1 BPM above the first.
     * Needs ≥4 samples to say anything — smaller windows return false.
     */
    fun isRisingHalf(window: List<Int>): Boolean {
        if (window.size < 4) return false
        val half = window.size / 2
        val firstHalf = window.take(half).average()
        val secondHalf = window.takeLast(half).average()
        return secondHalf > firstHalf + 1.0
    }

    /** HRR queue admission: peak far enough above rest AND high enough vs HRmax. */
    fun peakMeetsHrrThresholds(peakHr: Int, restingHr: Int, hrMax: Int): Boolean =
        peakHr - restingHr >= HRR_PEAK_MIN_RISE_BPM &&
            peakHr >= hrMax * HRR_PEAK_MIN_HRMAX_FRACTION
}
