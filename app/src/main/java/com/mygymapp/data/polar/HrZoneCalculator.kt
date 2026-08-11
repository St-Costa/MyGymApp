package com.mygymapp.data.polar

import java.time.Year

/** Z1-Z5 training zones, plus "below Z1" for rest-between-sets / pre-warmup HR. */
enum class HrZone { BELOW_Z1, Z1, Z2, Z3, Z4, Z5 }

/**
 * Live, on-device %HRR (Karvonen) zone classifier — the phone-side counterpart to the
 * sync server's retrospective `compute_hr_zone_minutes` (see `app/ecg_analysis.py` in
 * `MyGymApp_server` and `ECG_ADVANCED_ANALYSIS.md` §6 there). Deliberately mirrors that
 * formula so the live in-session zone agrees with what the dashboard shows after sync —
 * if one changes, the other should too.
 *
 * %HRR (not %HRmax) because %HRmax treats two people with the same max HR as equivalent
 * regardless of resting HR/cardiac reserve; %HRR corrects for that by anchoring the scale
 * to the individual's actual heart-rate reserve.
 *
 *   HRR = max_hr - resting_hr
 *   zone_boundary(pct) = resting_hr + pct * HRR
 *
 * If [restingHr] is unavailable (fresh install, no readiness history yet), falls back to
 * plain %HRmax (`pct * max_hr`, no resting-HR term) rather than blocking the feature —
 * same fallback the server already implements.
 */
class HrZoneCalculator(
    private val maxHr: Int,
    private val restingHr: Int?,
) {
    private val boundaries: List<Int> = listOf(0.50, 0.60, 0.70, 0.80, 0.90).map { pct ->
        if (restingHr != null) {
            (restingHr + pct * (maxHr - restingHr)).toInt()
        } else {
            (pct * maxHr).toInt()
        }
    }

    fun classify(bpm: Int): HrZone = when {
        bpm < boundaries[0] -> HrZone.BELOW_Z1
        bpm < boundaries[1] -> HrZone.Z1
        bpm < boundaries[2] -> HrZone.Z2
        bpm < boundaries[3] -> HrZone.Z3
        bpm < boundaries[4] -> HrZone.Z4
        else -> HrZone.Z5
    }

    /** %HRR (or %HRmax in the no-resting-HR fallback) for display, e.g. "Z3 · 74%". */
    fun percent(bpm: Int): Int = if (restingHr != null) {
        val hrr = (maxHr - restingHr).coerceAtLeast(1)
        (((bpm - restingHr).toDouble() / hrr) * 100).toInt().coerceIn(0, 999)
    } else {
        ((bpm.toDouble() / maxHr) * 100).toInt().coerceIn(0, 999)
    }

    companion object {
        /**
         * Mean of three published age-based formulas — mirrors the server's
         * `_estimated_max_hr` exactly (same three formulas, same unweighted mean).
         * Recomputed from the current date each time, not cached, so it drifts forward
         * automatically as the user ages.
         */
        fun estimatedMaxHr(birthYear: Int?, fallbackAge: Int): Int {
            val age = birthYear?.let { Year.now().value - it } ?: fallbackAge
            val fox = 220 - age
            val tanaka = 208 - 0.7 * age
            val gulati = 206 - 0.88 * age
            return ((fox + tanaka + gulati) / 3.0).toInt()
        }
    }
}
