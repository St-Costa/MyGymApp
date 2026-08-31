package com.mygymapp.data.polar

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure HRV-readiness math, split out of [PolarManager] so it can be unit-tested and — more
 * importantly — so the rolling baseline is derived from the persisted readiness `.md`
 * files rather than mirrored into a separate `SharedPreferences` blob.
 *
 * ## Why this exists (Phase 94)
 *
 * The baseline used to live only in `SharedPreferences("hrv_baseline")` — the one piece of
 * real user data in the app that wasn't a file under `gymdata/`. `SharedPreferences` is
 * wiped by `pm clear`, a differently-signed reinstall, or a partial restore that only
 * brings back `gymdata/` (exactly what happened on 2026-08-31: 15 historic readiness `.md`
 * files intact, baseline gone, so the next measurement reported "Collecting baseline data
 * (1/7 days)" and never computed a real readiness). The `.md` files are already the source
 * of truth for the readiness log, already in the backup tar, already synced — so the
 * baseline is now just `getAll().takeLast(N)` over them. No second state to keep in sync,
 * nothing left outside the backup.
 *
 * The windows (14 LnRMSSD samples, 7 resting-HR samples) and the z-score thresholds are
 * unchanged from the old `SharedPreferences` implementation.
 */
object HrvBaselineCalculator {

    /** Rolling window of LnRMSSD samples used for the z-score baseline. */
    const val LN_RMSSD_WINDOW = 14

    /** Rolling window of resting-HR samples; VO2max uses the min of these. */
    const val HR_REST_WINDOW = 7

    /** Minimum prior samples before a z-score (vs. "still collecting") is reported. */
    const val MIN_BASELINE_SAMPLES = 7

    /**
     * The LnRMSSD baseline to compare *today's* measurement against: the last
     * [LN_RMSSD_WINDOW] valid (`> 0`) LnRMSSD values from prior measurements, oldest first.
     *
     * [priorLnRmssd] must be the historic values in chronological order and must NOT
     * include the measurement being classified — mirrors the old code, where
     * `loadLnRmssdBaseline()` ran before `saveLnRmssdToBaseline(today)`.
     */
    fun lnRmssdBaseline(priorLnRmssd: List<Double>): List<Double> =
        priorLnRmssd.filter { it > 0.0 }.takeLast(LN_RMSSD_WINDOW)

    /**
     * The resting-HR baseline (last [HR_REST_WINDOW] valid readings, oldest first),
     * INCLUDING today's — matches the old `saveHrRestToBaseline(today)` then
     * `loadHrRestBaseline()` ordering. VO2max takes `.min()` of the result.
     */
    fun hrRestBaseline(recentRestingHrInclToday: List<Int>): List<Int> =
        recentRestingHrInclToday.filter { it > 0 }.takeLast(HR_REST_WINDOW)

    data class Classification(val readiness: Readiness, val recommendation: String)

    /**
     * Classify [lnRmssd] against [baseline] (already trimmed via [lnRmssdBaseline]).
     * Fewer than [MIN_BASELINE_SAMPLES] prior samples ⇒ [Readiness.NO_BASELINE] with a
     * "collecting" message; otherwise a z-score bucket. Thresholds unchanged from the
     * pre-Phase-94 `SharedPreferences` implementation.
     */
    fun classify(lnRmssd: Double, baseline: List<Double>): Classification {
        if (baseline.size < MIN_BASELINE_SAMPLES) {
            return Classification(
                Readiness.NO_BASELINE,
                "Collecting baseline data (${baseline.size + 1}/$MIN_BASELINE_SAMPLES days). LnRMSSD: %.1f".format(lnRmssd),
            )
        }
        val mean = baseline.average()
        val sd = sqrt(baseline.map { (it - mean).pow(2) }.average())
        val zScore = if (sd > 0) (lnRmssd - mean) / sd else 0.0

        val readiness = when {
            zScore < -1.5 -> Readiness.DELOAD_RECOMMENDED
            zScore < -1.0 -> Readiness.LIGHT_DAY
            zScore < 1.0 -> Readiness.NORMAL
            zScore > 1.5 -> Readiness.PEAK
            else -> Readiness.GOOD
        }
        val recommendation = when (readiness) {
            Readiness.DELOAD_RECOMMENDED ->
                "HRV significantly below baseline. Consider rest or light session."
            Readiness.LIGHT_DAY ->
                "HRV moderately suppressed. Reduce volume or intensity by 20%."
            Readiness.NORMAL ->
                "HRV within normal range. Proceed with planned workout."
            Readiness.GOOD ->
                "HRV above baseline. Good day to push intensity."
            Readiness.PEAK ->
                "HRV unusually high. Consider testing a PR."
            else -> ""
        }
        return Classification(readiness, recommendation)
    }
}
