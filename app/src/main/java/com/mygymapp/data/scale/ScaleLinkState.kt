package com.mygymapp.data.scale

/**
 * Explicit scale link state (ISM-21).
 *
 * Before this file existed `BleScaleManager`'s connection state was four
 * correlated pieces (`_connectionState`, `gatt`, `scanCallback`,
 * `autoConnectAttempted`) mutated across UI entry points, two timers and four
 * BLE callbacks, with the transition rules living only in the callback bodies
 * — including the "stuck in CONNECTING" shape (a failed service discovery or
 * a dead `connectGatt` leaves CONNECTING until a disconnect callback that may
 * never come, while `startScan` stays blocked). [reduceScaleLink] is the
 * single definition of every transition; the manager only executes the
 * entries' side effects (scanner, gatt, timers, lists). It mirrors the current
 * behavior exactly — including refusing a second `CONNECT_REQUEST` while
 * already connected/connecting (currently unreachable from the UI, and
 * executing it would leak the live `gatt`) and leaving a failed service
 * discovery in CONNECTING (quirk preserved and documented, not fixed blind:
 * without the device the retry/timeout policy can't be validated).
 * Adding a CONNECTING watchdog later is now one event + one arm.
 */

/** Every event that can move the scale link. */
enum class ScaleLinkEvent {
    START_SCAN,
    STOP_SCAN,
    SCAN_TIMEOUT,
    SCAN_FAILED,
    CONNECT_REQUEST,
    GATT_CONNECTED,
    SERVICES_READY,
    SERVICES_FAILED,
    GATT_DISCONNECTED,
    DISCONNECT_REQUEST,
}

/**
 * Pure transition table. Unlisted combinations keep the current state:
 * `START_SCAN`/`CONNECT_REQUEST` outside their origin states are ignored,
 * `GATT_CONNECTED` only triggers service discovery (a side effect, no state
 * change), and `DISCONNECT_REQUEST`/`SERVICES_FAILED` wait for the async
 * `GATT_DISCONNECTED` instead of flipping eagerly.
 */
fun reduceScaleLink(state: ScaleConnectionState, event: ScaleLinkEvent): ScaleConnectionState =
    when (event) {
        ScaleLinkEvent.GATT_DISCONNECTED -> ScaleConnectionState.DISCONNECTED
        ScaleLinkEvent.START_SCAN ->
            if (state == ScaleConnectionState.DISCONNECTED) ScaleConnectionState.SCANNING else state
        ScaleLinkEvent.STOP_SCAN, ScaleLinkEvent.SCAN_TIMEOUT, ScaleLinkEvent.SCAN_FAILED ->
            if (state == ScaleConnectionState.SCANNING) ScaleConnectionState.DISCONNECTED else state
        ScaleLinkEvent.CONNECT_REQUEST ->
            if (state == ScaleConnectionState.DISCONNECTED || state == ScaleConnectionState.SCANNING) {
                ScaleConnectionState.CONNECTING
            } else {
                state
            }
        ScaleLinkEvent.SERVICES_READY -> ScaleConnectionState.CONNECTED
        ScaleLinkEvent.GATT_CONNECTED,
        ScaleLinkEvent.SERVICES_FAILED,
        ScaleLinkEvent.DISCONNECT_REQUEST,
        -> state
    }

/**
 * Frame merge for the notify path: weight and body-composition arrive in
 * separate packets (commands `0x10`/`0x11`), each carrying only its own
 * fields. A present field always wins; stability is meaningful only on
 * weight frames, so a composition frame never flips it.
 */
fun ScaleReading.mergedWith(previous: ScaleReading?): ScaleReading = ScaleReading(
    weightKg = weightKg ?: previous?.weightKg,
    weightStable = if (weightKg != null) weightStable else (previous?.weightStable ?: false),
    impedanceOhm = impedanceOhm ?: previous?.impedanceOhm,
    displayUnit = displayUnit ?: previous?.displayUnit,
)

/**
 * Mirrors `maybeSaveWeighIn`'s entry guards: non-positive impedance is
 * partial contact, not a measurement, and without a tail-averaged weight
 * there is nothing to persist. Everything downstream (composition math,
 * profile/history/ledger writes, auto-disconnect push) runs only past this.
 */
fun shouldSaveWeighIn(impedanceOhm: Int, averageWeightKg: Double?): Boolean =
    impedanceOhm > 0 && averageWeightKg != null
