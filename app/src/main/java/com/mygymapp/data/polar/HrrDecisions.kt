package com.mygymapp.data.polar

/**
 * Pure HRR/recovery decision rules for the in-session capture (ISM-11).
 *
 * Before these existed `PolarManager.detectPeakAndTriggerRecovery`,
 * `updateRecoveryState` and `accumulateCaloriesAndTrimp` mixed *which numbers
 * count* with the mutable capture machinery (`hrWindow`, `pendingHrrPeaks`,
 * `isRecovering`, timestamps). The rules below are the single definition of
 * each decision — the manager only executes them — so what lands in
 * `hrrDeltas` vs `hrrDeltasDiscarded`, when the recovery semaphore clears,
 * and which samples feed calories/TRIMP is unit-testable without a strap.
 * The queue mechanics (debounce anchor, 60s settlement walk, series
 * accumulators) stay in `PolarManager`; the thresholds stay its constants and
 * are passed in, so there is exactly one value for each.
 */

// ─── Peak trigger ─────────────────────────────────────────────────────────────

data class PeakTrigger(
    /** Latch a new recovery semaphore (`peakHrAfterSet`, clear RR, drop RMSSD). */
    val startRecovery: Boolean,
    /** Enqueue an independent HRR 60s observation for this peak. */
    val queueHrr: Boolean,
)

/**
 * Mirrors the peak branch: a trigger needs was-rising-now-falling AND a peak
 * far enough above rest. Recovery latches only when none is active; the HRR
 * queue is independent (a second qualifying peak while recovering still queues
 * — `startRecovery == false` with `queueHrr == true` is the normal mid-circuit
 * case), gated by its own stricter thresholds plus the anti-double-count
 * debounce (strict `>`, same as the handler).
 */
fun peakTrigger(
    wasRising: Boolean,
    isRisingNow: Boolean,
    peakHr: Int,
    restingHr: Int,
    isRecovering: Boolean,
    meetsHrrThresholds: Boolean,
    nowMs: Long,
    lastQueuedAtMs: Long,
    minRiseBpm: Int,
    debounceMs: Long,
): PeakTrigger {
    if (!wasRising || isRisingNow) return PeakTrigger(false, false)
    if (peakHr - restingHr < minRiseBpm) return PeakTrigger(false, false)
    return PeakTrigger(
        startRecovery = !isRecovering,
        queueHrr = meetsHrrThresholds && nowMs - lastQueuedAtMs > debounceMs,
    )
}

// ─── 60s settlement ───────────────────────────────────────────────────────────

enum class HrrSettlement { ACCEPT, DISCARD_OUT_OF_RANGE, DISCARD_REBOUND }

/**
 * Mirrors the settlement precedence: the range gate runs FIRST — an
 * out-of-range delta is discarded without consulting monotonicity — then the
 * rebound gate. `delta = peak - hrAt60` with `hrAt60` the trailing-5s mean
 * from the series (see `hrrWindowAverage`), never the instantaneous sample.
 */
fun settleHrrPeak(peakHr: Int, hrAt60: Int, monotonic: Boolean): HrrSettlement {
    val delta = peakHr - hrAt60
    return when {
        delta !in 0..120 -> HrrSettlement.DISCARD_OUT_OF_RANGE
        monotonic -> HrrSettlement.ACCEPT
        else -> HrrSettlement.DISCARD_REBOUND
    }
}

fun hrrDelta(peakHr: Int, hrAt60: Int): Int = peakHr - hrAt60

// ─── Recovery exit ────────────────────────────────────────────────────────────

/**
 * Mirrors `updateRecoveryState`: HR must have fallen [hrFraction] of the way
 * from peak to rest (`>=`, and vacuously true when the peak never rose above
 * rest) AND parasympathetic reactivation must show (`rmssd` strictly above
 * threshold with at least [minRecentRr] intervals).
 */
fun hasRecovered(
    peakHrAfterSet: Int,
    restingHr: Int,
    currentHr: Int,
    rmssd: Double,
    recentRrCount: Int,
    hrFraction: Float,
    rmssdThreshold: Double,
    minRecentRr: Int,
): Boolean {
    val hrDelta = peakHrAfterSet - restingHr
    val hrReady = if (hrDelta > 0) {
        (peakHrAfterSet - currentHr).toFloat() / hrDelta >= hrFraction
    } else {
        true
    }
    return hrReady && rmssd > rmssdThreshold && recentRrCount >= minRecentRr
}

// ─── Sample window ────────────────────────────────────────────────────────────

/**
 * Mirrors the calorie/TRIMP guard: only a positive interval within the
 * plausibility window feeds the accumulators (a >10s gap means reconnection,
 * not effort). `null` means skip — the caller still advances its timestamp.
 */
fun clampSampleMinutes(elapsedMin: Double, maxGapMin: Double = 0.2): Double? =
    elapsedMin.takeIf { it > 0 && it <= maxGapMin }
