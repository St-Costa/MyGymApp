package com.mygymapp.data.polar

/**
 * One persisted 60s readiness measurement — the result [PolarManager.finishReadinessMeasurement]
 * already computes, but historically only ever held in the `readinessResult` StateFlow for
 * the UI and never written to disk. Persisted so it can be synced to the self-hosted
 * server immediately (docs/SYNC.md), independent of whether/when the user completes a
 * workout session that day.
 */
data class ReadinessEvent(
    val id: String,
    val measuredAt: String,       // ISO-8601 LocalDateTime
    val readiness: String,        // Readiness enum name
    val lnRmssd: Double,
    val restingHr: Int,
    val vo2max: Double,
    val recommendation: String,
)
