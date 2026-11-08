package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure recovery decisions (ISM-07/ISM-09): [reconnectTick],
 * [anchorReconnectStart], [hrWatchdogDecision], [ecgWatchdogDecision],
 * [shouldParkForStandby] and [shouldEnsureStreaming]. Each row mirrors one
 * branch of the `PolarManager` handler it was extracted from, including the
 * strict-`>` boundaries — a drift in either direction fails here first.
 */
class LinkRecoveryTest {

    // ─── Reconnect tick ───

    @Test
    fun `user hang-up or restored link aborts without rescheduling`() {
        assertEquals(
            ReconnectTick.AbortStale,
            reconnectTick(true, null, nowMs = 10_000L, reconnectStartAtMs = 0L),
        )
        assertEquals(
            ReconnectTick.AbortStale,
            reconnectTick(false, "aa:bb:cc", nowMs = 10_000L, reconnectStartAtMs = 0L),
        )
    }

    @Test
    fun `past the 5-minute window the loop gives up`() {
        assertEquals(
            ReconnectTick.GiveUp,
            reconnectTick(
                false, null,
                nowMs = RECONNECT_TIMEOUT_MS + 1,
                reconnectStartAtMs = 0L,
            ),
        )
    }

    @Test
    fun `exactly at the timeout boundary it still attempts once more`() {
        // Strict `>`, same as the handler: `==` is not past the window.
        assertEquals(
            ReconnectTick.Attempt,
            reconnectTick(
                false, null,
                nowMs = RECONNECT_TIMEOUT_MS,
                reconnectStartAtMs = 0L,
            ),
        )
    }

    @Test
    fun `inside the window it attempts`() {
        assertEquals(
            ReconnectTick.Attempt,
            reconnectTick(false, null, nowMs = 60_000L, reconnectStartAtMs = 0L),
        )
    }

    @Test
    fun `reconnect window anchors at the first call`() {
        assertEquals(9_000L, anchorReconnectStart(0L, 9_000L))
        assertEquals(9_000L, anchorReconnectStart(9_000L, 20_000L))
    }

    // ─── HR watchdog ───

    @Test
    fun `fresh samples trigger nothing`() {
        val d = hrWatchdogDecision(
            receivingData = true, lastHrSampleAtMs = 9_500L, hrStreamRunning = true,
            nowMs = 10_000L, staleAfterMs = 5_000L,
        )
        assertFalse(d.markStaleDisplay)
        assertFalse(d.restartStream)
    }

    @Test
    fun `silent-but-linked marks stale display without restarting`() {
        // Stream object gone (parked), samples old: UI shows NO_SIGNAL, no resubscribe.
        val d = hrWatchdogDecision(
            receivingData = true, lastHrSampleAtMs = 1_000L, hrStreamRunning = false,
            nowMs = 10_000L, staleAfterMs = 5_000L,
        )
        assertTrue(d.markStaleDisplay)
        assertFalse(d.restartStream)
    }

    @Test
    fun `subscribed-but-silent both marks stale and restarts`() {
        val d = hrWatchdogDecision(
            receivingData = true, lastHrSampleAtMs = 1_000L, hrStreamRunning = true,
            nowMs = 30_000L, staleAfterMs = 5_000L,
        )
        assertTrue(d.markStaleDisplay)
        assertTrue(d.restartStream)
    }

    @Test
    fun `never-sampled stream triggers neither`() {
        val d = hrWatchdogDecision(
            receivingData = false, lastHrSampleAtMs = 0L, hrStreamRunning = true,
            nowMs = 60_000L, staleAfterMs = 5_000L,
        )
        assertFalse(d.markStaleDisplay)
        assertFalse(d.restartStream)
    }

    @Test
    fun `stale boundary is strict`() {
        val at = hrWatchdogDecision(
            receivingData = true, lastHrSampleAtMs = 5_000L, hrStreamRunning = false,
            nowMs = 10_000L, staleAfterMs = 5_000L,
        )
        assertFalse(at.markStaleDisplay)
    }

    // ─── ECG watchdog ───

    @Test
    fun `inactive session, unready feature, or no stream never restarts`() {
        for (d in listOf(
            ecgWatchdogDecision(false, true, true, 1_000L, 500L, 20_000L),
            ecgWatchdogDecision(true, false, true, 1_000L, 500L, 20_000L),
            ecgWatchdogDecision(true, true, false, 1_000L, 500L, 20_000L),
        )) assertFalse(d.shouldRestart)
    }

    @Test
    fun `started-but-silent stream restarts with the first-sample reason`() {
        val d = ecgWatchdogDecision(
            hasActiveSession = true, streamingFeatureReady = true, ecgStreaming = true,
            lastEcgSampleAtMs = 0L, ecgStreamStartedAtMs = 1_000L, nowMs = 20_000L,
        )
        assertTrue(d.shouldRestart)
        assertEquals("no first sample after 19s", d.reason)
    }

    @Test
    fun `stalled samples restart with the stall reason`() {
        val d = ecgWatchdogDecision(
            hasActiveSession = true, streamingFeatureReady = true, ecgStreaming = true,
            lastEcgSampleAtMs = 1_000L, ecgStreamStartedAtMs = 500L, nowMs = 20_000L,
        )
        assertTrue(d.shouldRestart)
        assertEquals("no sample for 19s", d.reason)
    }

    @Test
    fun `flowing samples never restart`() {
        val d = ecgWatchdogDecision(
            hasActiveSession = true, streamingFeatureReady = true, ecgStreaming = true,
            lastEcgSampleAtMs = 19_000L, ecgStreamStartedAtMs = 500L, nowMs = 20_000L,
        )
        assertFalse(d.shouldRestart)
    }

    // ─── Standby guards ───

    @Test
    fun `park only on a live idle link`() {
        // Connected + idle + flowing -> park.
        assertTrue(shouldParkForStandby(true, false, false, true, false))
        // Connected + idle + nothing flowing + not parked -> park (first entry).
        assertTrue(shouldParkForStandby(true, false, false, false, false))
        // Busy with a session or a measurement -> keep.
        assertFalse(shouldParkForStandby(true, true, false, true, false))
        assertFalse(shouldParkForStandby(true, false, true, true, false))
        // Already parked with nothing flowing -> keep (no-op).
        assertFalse(shouldParkForStandby(true, false, false, false, true))
        // Not connected -> never park (caller clears standby instead).
        assertFalse(shouldParkForStandby(false, false, false, false, false))
    }

    @Test
    fun `ensure streaming skips only an already-flowing stream`() {
        assertFalse(shouldEnsureStreaming(true, true, false))
        assertTrue(shouldEnsureStreaming(true, true, true))
        assertTrue(shouldEnsureStreaming(true, false, false))
        assertFalse(shouldEnsureStreaming(false, false, false))
    }
}
