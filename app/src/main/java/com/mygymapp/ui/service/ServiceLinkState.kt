package com.mygymapp.ui.service

/**
 * Explicit notification-mirror state for [PolarStreamingService] (ISM-14).
 *
 * Before this type existed the service's visible state was four correlated
 * fields (`isRunning × reconnecting × standby × currentHr`) mutated across five
 * intent actions, with the display priority (`reconnecting > standby > HR >
 * connecting`) living only in `buildNotification`'s `when`. [ServiceLinkState]
 * is the single definition: every action maps to one state, and
 * [serviceNotificationTitle] is the single definition of what the user reads.
 * `isRunning` (service lifecycle) and `deviceName` (display-only label) stay
 * outside on purpose — they never constrained each other, so folding them in
 * would create combinations, not remove them.
 *
 * `SCANNING` is the connection screen's scan-keepalive mode: the BLE scan must
 * survive screen-off, so the same foreground service is used while waiting for a
 * known strap to appear. It is not a link state — the notification just says
 * "Searching for Polar…" instead of the misleading "Connecting…".
 */
enum class ServiceLinkAction { START, SCANNING, UPDATE_HR, RECONNECTING, STANDBY, STOP }

data class ServiceLinkState(
    val reconnecting: Boolean = false,
    val scanning: Boolean = false,
    val standby: Boolean = false,
    val currentHr: Int? = null,
) {
    /**
     * Pure reducer over the `onStartCommand` actions. Mirrors the handler
     * exactly: START/SCANNING clear everything; UPDATE_HR (already validated
     * `hr > 0` by the caller — non-positive values never reach the service)
     * clears the flags and shows the sample; RECONNECTING/STANDBY clear each
     * other and the sample; STOP leaves the mirror untouched (teardown is
     * `isRunning = false` plus notification cancel, handled by the service).
     */
    fun onAction(action: ServiceLinkAction, hr: Int = 0): ServiceLinkState = when (action) {
        ServiceLinkAction.START -> ServiceLinkState()
        ServiceLinkAction.SCANNING -> ServiceLinkState(scanning = true)
        ServiceLinkAction.UPDATE_HR -> ServiceLinkState(currentHr = hr)
        ServiceLinkAction.RECONNECTING -> ServiceLinkState(reconnecting = true)
        ServiceLinkAction.STANDBY -> ServiceLinkState(standby = true)
        ServiceLinkAction.STOP -> this
    }
}

/**
 * The `buildNotification` priority, extracted so it stays in sync by
 * construction: an in-flight reconnect outranks everything, a scan-only
 * keepalive (which can never carry an HR sample) outranks standby and a stale
 * sample, otherwise standby, the latest HR, then connecting.
 */
fun serviceNotificationTitle(state: ServiceLinkState): String = when {
    state.reconnecting -> "Reconnecting…"
    state.scanning -> "Searching for Polar…"
    state.standby -> "Standby · stream paused"
    state.currentHr != null -> "${state.currentHr} BPM"
    else -> "Connecting…"
}
