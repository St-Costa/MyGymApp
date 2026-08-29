package com.mygymapp.data.polar

/**
 * The Polar link state the UI actually cares about — a refinement of [ConnectionState]
 * that splits the single `CONNECTED` value into "connected and delivering HR samples" vs
 * "connected at the BLE level but silent" (the strap was taken off the chest band or its
 * battery pulled — Android hasn't torn the link down yet, and may still be auto-reconnecting).
 *
 * This never changes the real connection: it is a view over [PolarManager.connectionState]
 * plus a data-presence flag driven by the HR-sample watchdog. See [linkStatusOf].
 */
enum class PolarLinkStatus {
    /** BLE connected and an HR sample arrived within the freshness window. */
    CONNECTED,

    /**
     * Either (a) BLE still connected but no HR sample for a while, or (b) the link just
     * dropped outside a workout and we're inside the post-drop grace window while
     * Android/SDK may still auto-reconnect. Both mean "strap removed / powered off, not
     * sending data" — shown the same way (⚠️).
     */
    NO_SIGNAL,

    /** First handshake, or Android/SDK retrying after an unexpected drop mid-session. */
    CONNECTING,

    /** No link — never connected, user disconnected, or Android gave up (grace expired). */
    DISCONNECTED,
}

/**
 * Pure mapping from the raw [ConnectionState] + two flags to the [PolarLinkStatus] the UI
 * shows.
 *
 * @param receivingData whether an HR sample landed within the freshness window. Only
 *   meaningful while [state] is `CONNECTED`.
 * @param noSignalGrace whether we're inside the short post-drop window (set by
 *   [PolarManager] when the strap vanishes outside a session). When true and the link is
 *   not actually up, the status is NO_SIGNAL rather than DISCONNECTED/CONNECTING, so the
 *   UI shows ⚠️ "no signal" while a reconnect is still plausible.
 */
fun linkStatusOf(
    state: ConnectionState,
    receivingData: Boolean,
    noSignalGrace: Boolean = false,
): PolarLinkStatus =
    when (state) {
        ConnectionState.CONNECTED ->
            if (receivingData) PolarLinkStatus.CONNECTED else PolarLinkStatus.NO_SIGNAL
        ConnectionState.CONNECTING ->
            if (noSignalGrace) PolarLinkStatus.NO_SIGNAL else PolarLinkStatus.CONNECTING
        ConnectionState.DISCONNECTED ->
            if (noSignalGrace) PolarLinkStatus.NO_SIGNAL else PolarLinkStatus.DISCONNECTED
    }
