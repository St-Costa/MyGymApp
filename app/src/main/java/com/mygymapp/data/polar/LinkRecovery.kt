package com.mygymapp.data.polar

/**
 * Pure recovery decisions for the Polar link (ISM-07/ISM-09).
 *
 * Before these existed the reconnect loop, the 1s data-presence watchdog and
 * the standby guards mixed *what to do* with *how to schedule it*
 * (`Handler.postDelayed`, disposables, SDK calls) inside `PolarManager`
 * callbacks, with the thresholds as inline literals. The decisions below are
 * the single definition of each rule — `PolarManager` only executes them —
 * so the backoff cap, the stale-vs-restart split and the standby entry
 * conditions are unit-testable without a strap, and can't drift between the
 * watchdog and the restart paths that share them.
 */

// ─── Reconnect loop ───────────────────────────────────────────────────────────

const val RECONNECT_FIRST_DELAY_MS = 3_000L
const val RECONNECT_RETRY_DELAY_MS = 10_000L
const val RECONNECT_TIMEOUT_MS = 5 * 60_000L

/** Outcome of one `scheduleReconnect` tick. */
enum class ReconnectTick {
    /** Link came back or the user hung up meanwhile — stop without rescheduling. */
    AbortStale,
    /** 5 minutes elapsed with no link — give up, drop the session capture. */
    GiveUp,
    /** Still down and inside the window — attempt `connectToDevice`, retry in 10s. */
    Attempt,
}

/**
 * Mirrors the `scheduleReconnect` runnable body: a user hang-up or an already
 * restored link aborts; past [timeoutMs] the loop gives up (strict `>`, same
 * as the handler code — an exactly-5-minute-old anchor still attempts once
 * more); otherwise attempt.
 */
fun reconnectTick(
    userInitiatedDisconnect: Boolean,
    connectedDeviceId: String?,
    nowMs: Long,
    reconnectStartAtMs: Long,
    timeoutMs: Long = RECONNECT_TIMEOUT_MS,
): ReconnectTick = when {
    userInitiatedDisconnect || connectedDeviceId != null -> ReconnectTick.AbortStale
    nowMs - reconnectStartAtMs > timeoutMs -> ReconnectTick.GiveUp
    else -> ReconnectTick.Attempt
}

/** The reconnect window anchors at the first `scheduleReconnect` call. */
fun anchorReconnectStart(currentAnchorMs: Long, nowMs: Long): Long =
    if (currentAnchorMs == 0L) nowMs else currentAnchorMs

// ─── HR watchdog ──────────────────────────────────────────────────────────────

const val HR_STREAM_RESTART_AFTER_MS = 15_000L

/** Both HR checks run independently every tick (display-only first, restart second). */
data class HrWatchdogDecision(
    /** Strap still linked but silent past the freshness window: show NO_SIGNAL. */
    val markStaleDisplay: Boolean,
    /** Stream subscribed but delivering nothing: resubscribe. */
    val restartStream: Boolean,
)

/**
 * Mirrors the HR half of the watchdog runnable: staleness is display-only
 * (promotion back to receiving happens solely on a real sample, never here),
 * while a subscribed-but-silent stream resubscribes. A never-sampled stream
 * (`lastHrSampleAtMs == 0`) triggers neither — same as the handler code.
 */
fun hrWatchdogDecision(
    receivingData: Boolean,
    lastHrSampleAtMs: Long,
    hrStreamRunning: Boolean,
    nowMs: Long,
    staleAfterMs: Long,
    restartAfterMs: Long = HR_STREAM_RESTART_AFTER_MS,
): HrWatchdogDecision {
    val ageOk = lastHrSampleAtMs > 0
    return HrWatchdogDecision(
        markStaleDisplay = receivingData && ageOk && nowMs - lastHrSampleAtMs > staleAfterMs,
        restartStream = hrStreamRunning && ageOk && nowMs - lastHrSampleAtMs > restartAfterMs,
    )
}

// ─── ECG watchdog ─────────────────────────────────────────────────────────────

const val ECG_FIRST_SAMPLE_TIMEOUT_MS = 15_000L
const val ECG_SAMPLE_TIMEOUT_MS = 10_000L

data class EcgWatchdogDecision(val shouldRestart: Boolean, val reason: String)

/**
 * Mirrors the ECG half of the watchdog runnable: restart when samples were
 * flowing but stopped, or when a started stream never delivered a first sample
 * (silent hang). Strings match the handler's log lines so the log stays
 * greppable. The caller routes through `scheduleEcgRestart` so watchdog
 * restarts count toward the escalation cap.
 */
fun ecgWatchdogDecision(
    hasActiveSession: Boolean,
    streamingFeatureReady: Boolean,
    ecgStreaming: Boolean,
    lastEcgSampleAtMs: Long,
    ecgStreamStartedAtMs: Long,
    nowMs: Long,
    firstSampleTimeoutMs: Long = ECG_FIRST_SAMPLE_TIMEOUT_MS,
    sampleTimeoutMs: Long = ECG_SAMPLE_TIMEOUT_MS,
): EcgWatchdogDecision {
    if (!hasActiveSession || !streamingFeatureReady || !ecgStreaming) {
        return EcgWatchdogDecision(false, "")
    }
    val noFirstSample = lastEcgSampleAtMs == 0L &&
        ecgStreamStartedAtMs > 0 &&
        nowMs - ecgStreamStartedAtMs > firstSampleTimeoutMs
    val sampleTimeout = lastEcgSampleAtMs > 0 &&
        nowMs - lastEcgSampleAtMs > sampleTimeoutMs
    if (!noFirstSample && !sampleTimeout) return EcgWatchdogDecision(false, "")
    val reason = if (noFirstSample) {
        "no first sample after ${(nowMs - ecgStreamStartedAtMs) / 1000}s"
    } else {
        "no sample for ${(nowMs - lastEcgSampleAtMs) / 1000}s"
    }
    return EcgWatchdogDecision(true, reason)
}

// ─── Standby guards ───────────────────────────────────────────────────────────

/**
 * Mirrors `enterHrStandby`'s entry conditions: park only on a live link while
 * fully idle (no session capture, no readiness measurement), and skip when
 * already parked with nothing flowing. The not-connected branch (which *clears*
 * standby) stays in `PolarManager` — this answers only "park or keep".
 */
fun shouldParkForStandby(
    connected: Boolean,
    hrSeriesActive: Boolean,
    readinessMeasuring: Boolean,
    streamRunning: Boolean,
    alreadyParked: Boolean,
): Boolean = connected && !hrSeriesActive && !readinessMeasuring &&
    (streamRunning || !alreadyParked)

/**
 * Mirrors `ensureHrStreaming`'s early return: skip only when a stream is
 * already flowing outside standby; a parked strap always resubscribes.
 */
fun shouldEnsureStreaming(
    connected: Boolean,
    streamRunning: Boolean,
    inStandby: Boolean,
): Boolean = connected && !(streamRunning && !inStandby)
