package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [linkStatusOf] — the pure mapping from raw [ConnectionState] + "is HR data
 * flowing?" to the [PolarLinkStatus] every Polar-aware screen renders. This is the whole of
 * the "connesso but no signal" behaviour; the watchdog that drives the boolean is I/O.
 */
class PolarLinkStatusTest {

    @Test
    fun disconnected_ignores_data_flag() {
        assertEquals(
            PolarLinkStatus.DISCONNECTED,
            linkStatusOf(ConnectionState.DISCONNECTED, receivingData = false),
        )
        assertEquals(
            PolarLinkStatus.DISCONNECTED,
            linkStatusOf(ConnectionState.DISCONNECTED, receivingData = true),
        )
    }

    @Test
    fun connecting_ignores_data_flag() {
        assertEquals(
            PolarLinkStatus.CONNECTING,
            linkStatusOf(ConnectionState.CONNECTING, receivingData = false),
        )
        assertEquals(
            PolarLinkStatus.CONNECTING,
            linkStatusOf(ConnectionState.CONNECTING, receivingData = true),
        )
    }

    @Test
    fun connected_with_data_is_connected() {
        assertEquals(
            PolarLinkStatus.CONNECTED,
            linkStatusOf(ConnectionState.CONNECTED, receivingData = true),
        )
    }

    @Test
    fun connected_without_data_is_no_signal() {
        assertEquals(
            PolarLinkStatus.NO_SIGNAL,
            linkStatusOf(ConnectionState.CONNECTED, receivingData = false),
        )
    }

    @Test
    fun connected_ignores_grace_flag() {
        // A real, data-flowing connection always wins over a stale grace flag.
        assertEquals(
            PolarLinkStatus.CONNECTED,
            linkStatusOf(ConnectionState.CONNECTED, receivingData = true, noSignalGrace = true),
        )
    }

    @Test
    fun disconnected_within_grace_is_no_signal() {
        assertEquals(
            PolarLinkStatus.NO_SIGNAL,
            linkStatusOf(ConnectionState.DISCONNECTED, receivingData = false, noSignalGrace = true),
        )
    }

    @Test
    fun disconnected_after_grace_is_disconnected() {
        assertEquals(
            PolarLinkStatus.DISCONNECTED,
            linkStatusOf(ConnectionState.DISCONNECTED, receivingData = false, noSignalGrace = false),
        )
    }

    @Test
    fun connecting_within_grace_is_no_signal_not_connecting() {
        // Mid-session reconnect path sets CONNECTING; if a grace window is also active
        // (out-of-session drop that then entered the reconnect loop) we prefer the ⚠️.
        assertEquals(
            PolarLinkStatus.NO_SIGNAL,
            linkStatusOf(ConnectionState.CONNECTING, receivingData = false, noSignalGrace = true),
        )
    }
}
