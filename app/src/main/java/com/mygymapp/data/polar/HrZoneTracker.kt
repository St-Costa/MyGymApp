package com.mygymapp.data.polar

/** Running per-zone minutes for the in-progress session (live "time in HR zone" widget). */
data class HrZoneMinutes(
    val belowZone1: Double = 0.0,
    val zone1: Double = 0.0,
    val zone2: Double = 0.0,
    val zone3: Double = 0.0,
    val zone4: Double = 0.0,
    val zone5: Double = 0.0,
) {
    val total: Double get() = belowZone1 + zone1 + zone2 + zone3 + zone4 + zone5
}

/**
 * In-memory accumulator for live time-in-zone during a session — the on-device,
 * real-time counterpart to the sync server's retrospective `compute_hr_zone_minutes`
 * (see [HrZoneCalculator]). Not persisted: the server-side ECG upload/analysis after the
 * session ends remains the durable, retrospective source of truth for historical zone
 * data. This is a real-time in-workout convenience only.
 *
 * Mirrors the server's own logic: each inter-tick gap is attributed entirely to the zone
 * the *leading* (previous) reading falls in — coarse but standard, since HR is fairly
 * stable within a ~1s gap.
 */
class HrZoneTracker {
    private var minutes = HrZoneMinutes()
    private var lastTickAtMs: Long = 0L
    private var lastZone: HrZone? = null

    val current: HrZoneMinutes get() = minutes

    fun reset() {
        minutes = HrZoneMinutes()
        lastTickAtMs = 0L
        lastZone = null
    }

    /** Call on every BPM update. [zone] is the classification of the *new* [bpm]. */
    fun onTick(zone: HrZone, nowMs: Long = System.currentTimeMillis()) {
        val gap = zoneGapMinutes(lastTickAtMs, nowMs)
        val prevZone = lastZone
        if (gap != null && prevZone != null) {
            // Attributed to the leading (previous) zone: HR is fairly stable within
            // a ~1s gap, so this coarse attribution is standard.
            minutes = minutes.addTo(prevZone, gap)
        }
        lastTickAtMs = nowMs
        lastZone = zone
    }

    private fun HrZoneMinutes.addTo(zone: HrZone, dt: Double): HrZoneMinutes = when (zone) {
        HrZone.BELOW_Z1 -> copy(belowZone1 = belowZone1 + dt)
        HrZone.Z1 -> copy(zone1 = zone1 + dt)
        HrZone.Z2 -> copy(zone2 = zone2 + dt)
        HrZone.Z3 -> copy(zone3 = zone3 + dt)
        HrZone.Z4 -> copy(zone4 = zone4 + dt)
        HrZone.Z5 -> copy(zone5 = zone5 + dt)
    }
}

/**
 * Gap veto for [HrZoneTracker.onTick] (second sweep): a gap is attributed only
 * when a previous tick exists and the gap is plausible (0..0.2 min) — reconnects
 * and backgrounded gaps are dropped instead of misattributed to whatever zone
 * the stream happened to be in beforehand. `null` means drop.
 */
internal fun zoneGapMinutes(lastTickAtMs: Long, nowMs: Long): Double? {
    if (lastTickAtMs == 0L) return null
    return ((nowMs - lastTickAtMs) / 60_000.0).takeIf { it in 0.0..0.2 }
}
