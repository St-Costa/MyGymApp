package com.mygymapp.data.polar

/**
 * One persisted 60s readiness measurement — the result [PolarManager.finishReadinessMeasurement]
 * already computes, but historically only ever held in the `readinessResult` StateFlow for
 * the UI and never written to disk. Persisted so it can be synced to the self-hosted
 * server immediately (docs/SYNC.md), independent of whether/when the user completes a
 * workout session that day.
 *
 * [stepsAvgPerDay]/[stepsDaysSpanned] piggyback on this same daily event rather than getting
 * their own record type: the readiness test is already the app's one guaranteed daily
 * touchpoint (see `StepLedgerRepository`), so there's no independent trigger that would
 * justify a separate sync pipeline. Both are `null` when no previous step checkpoint existed
 * yet to diff against (e.g. the very first readiness test ever, or the sensor/permission
 * unavailable) — distinct from `0.0`, which would claim "zero steps" was actually measured.
 */
data class ReadinessEvent(
    val id: String,
    val measuredAt: String,       // ISO-8601 LocalDateTime
    val readiness: String,        // Readiness enum name
    val lnRmssd: Double,
    val restingHr: Int,
    val vo2max: Double,
    val recommendation: String,
    val stepsAvgPerDay: Double? = null,
    val stepsDaysSpanned: Int? = null,
)
