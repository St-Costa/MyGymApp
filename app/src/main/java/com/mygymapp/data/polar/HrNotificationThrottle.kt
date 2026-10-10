package com.mygymapp.data.polar

/**
 * Throttle for the foreground-service HR notification (Sprint 2).
 *
 * `PolarStreamingService.updateHr()` used to fire an intent on EVERY HR sample (~1 Hz,
 * ~3600 intents/hour) just to repaint a notification nobody is looking at mid-set — and
 * each `startService` from a backgrounded app risks
 * `BackgroundServiceStartNotAllowedException` inside the BLE callback's `onNext`, which
 * Rx routes to `onError` and kicks a futile 2s restart loop. Sending at most every
 * [MIN_INTERVAL_MS] (plus an immediate send on a large jump) cuts the intent rate ~5x
 * while the displayed BPM stays within a beat or two of live.
 *
 * Pure (caller passes `nowMs`) so it is unit-testable without Android.
 */
class HrNotificationThrottle(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val forceDeltaBpm: Int = FORCE_DELTA_BPM,
) {
    private var lastSentHr: Int? = null
    private var lastSentMs: Long = 0L

    /**
     * True when the notification should be refreshed for [hr] at [nowMs]. Records the
     * send, so callers only invoke the service when this returns true.
     */
    fun shouldSend(hr: Int, nowMs: Long): Boolean {
        val last = lastSentHr
        if (last == null) {
            lastSentHr = hr
            lastSentMs = nowMs
            return true
        }
        if (hr == last) return false
        if (nowMs - lastSentMs >= minIntervalMs || kotlin.math.abs(hr - last) >= forceDeltaBpm) {
            lastSentHr = hr
            lastSentMs = nowMs
            return true
        }
        return false
    }

    companion object {
        const val MIN_INTERVAL_MS = 5_000L
        const val FORCE_DELTA_BPM = 5
    }
}
