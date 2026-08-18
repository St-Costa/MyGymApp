package com.mygymapp.data.polar

/**
 * Pure logic for the HRR60s monotonicity/rebound check, split out of [PolarManager] so it can
 * be unit-tested without the rest of that class's Android/Hilt/BLE dependencies.
 *
 * True passive HR recovery is monotonically decreasing. This walks the HR samples between a
 * peak and the sample taken at peak+60s tracking a running minimum; if HR ever rebounds more
 * than [toleranceBpm] above that running minimum, the subject most likely resumed activity
 * (another exercise, walking, talking) instead of actually resting, so the delta to the +60s
 * sample doesn't measure recovery and should be discarded — see
 * PolarManager.HRR_REBOUND_TOLERANCE_BPM and MyGymApp_server's app/ecg_analysis.py
 * `_hr_recovery_at` for the equivalent server-side implementation this mirrors.
 *
 * @param series (elapsedMs, hr) samples, in chronological order — e.g. PolarManager's hrSeries.
 * @param peakHr the peak HR value that started the recovery window.
 * @param peakElapsedMs elapsed-ms timestamp of the peak (same clock basis as [series]).
 * @param sampleElapsedMs elapsed-ms timestamp of the +60s sample (same clock basis as [series]).
 * @param toleranceBpm bpm of rebound tolerated above the running minimum before discarding.
 */
fun isMonotonicHrRecovery(
    series: Iterable<Pair<Long, Int>>,
    peakHr: Int,
    peakElapsedMs: Long,
    sampleElapsedMs: Long,
    toleranceBpm: Int,
): Boolean {
    var runningMin = peakHr
    for ((elapsed, hrSample) in series) {
        if (elapsed < peakElapsedMs) continue
        if (elapsed > sampleElapsedMs) break
        if (hrSample < runningMin) runningMin = hrSample
        if (hrSample - runningMin > toleranceBpm) return false
    }
    return true
}
