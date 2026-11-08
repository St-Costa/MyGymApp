package com.mygymapp.data.scale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit scale link machine (ISM-21): [reduceScaleLink],
 * [ScaleReading.mergedWith] and [shouldSaveWeighIn]. Each transition row
 * mirrors one `BleScaleManager` callback branch — including the preserved
 * quirks (failed discovery stays CONNECTING, disconnect waits for the
 * callback) and the refused reconnect-while-connected.
 */
class ScaleLinkStateTest {

    private val D = ScaleConnectionState.DISCONNECTED
    private val S = ScaleConnectionState.SCANNING
    private val C = ScaleConnectionState.CONNECTING
    private val O = ScaleConnectionState.CONNECTED

    // ─── Reducer ───

    @Test
    fun `scan starts only from disconnected`() {
        assertEquals(S, reduceScaleLink(D, ScaleLinkEvent.START_SCAN))
        assertEquals(S, reduceScaleLink(S, ScaleLinkEvent.START_SCAN))
        assertEquals(C, reduceScaleLink(C, ScaleLinkEvent.START_SCAN))
        assertEquals(O, reduceScaleLink(O, ScaleLinkEvent.START_SCAN))
    }

    @Test
    fun `stop timeout and failure only leave scanning`() {
        for (event in listOf(
            ScaleLinkEvent.STOP_SCAN,
            ScaleLinkEvent.SCAN_TIMEOUT,
            ScaleLinkEvent.SCAN_FAILED,
        )) {
            assertEquals(D, reduceScaleLink(S, event))
            assertEquals(D, reduceScaleLink(D, event))
            assertEquals(C, reduceScaleLink(C, event))
            assertEquals(O, reduceScaleLink(O, event))
        }
    }

    @Test
    fun `connect proceeds from idle or scanning, refused otherwise`() {
        assertEquals(C, reduceScaleLink(D, ScaleLinkEvent.CONNECT_REQUEST))
        // SCANNING is stopped first by connectToDevice, then this arm runs.
        assertEquals(C, reduceScaleLink(S, ScaleLinkEvent.CONNECT_REQUEST))
        assertEquals(C, reduceScaleLink(C, ScaleLinkEvent.CONNECT_REQUEST))
        assertEquals(O, reduceScaleLink(O, ScaleLinkEvent.CONNECT_REQUEST))
    }

    @Test
    fun `link-up needs service discovery, link-down is unconditional`() {
        // GATT_CONNECTED only triggers discovery — never a state change.
        for (state in ScaleConnectionState.entries) {
            assertEquals(state, reduceScaleLink(state, ScaleLinkEvent.GATT_CONNECTED))
        }
        assertEquals(O, reduceScaleLink(C, ScaleLinkEvent.SERVICES_READY))
        // Failed discovery preserves the stuck-CONNECTING quirk (documented, not fixed blind).
        assertEquals(C, reduceScaleLink(C, ScaleLinkEvent.SERVICES_FAILED))
        for (state in ScaleConnectionState.entries) {
            assertEquals(D, reduceScaleLink(state, ScaleLinkEvent.GATT_DISCONNECTED))
        }
        // Disconnect is async: the request changes nothing, the callback flips.
        for (state in ScaleConnectionState.entries) {
            assertEquals(state, reduceScaleLink(state, ScaleLinkEvent.DISCONNECT_REQUEST))
        }
    }

    // ─── Frame merge ───

    private fun reading(
        weightKg: Double? = null,
        weightStable: Boolean = false,
        impedanceOhm: Int? = null,
    ) = ScaleReading(
        weightKg = weightKg,
        weightStable = weightStable,
        impedanceOhm = impedanceOhm,
        displayUnit = null,
    )

    @Test
    fun `weight and composition frames merge without clobbering each other`() {
        val weight = reading(weightKg = 70.5, weightStable = true)
        val merged = weight.mergedWith(null)
        assertEquals(70.5, merged.weightKg!!, 0.0)
        assertTrue(merged.weightStable)
        assertNull(merged.impedanceOhm)

        val compo = reading(impedanceOhm = 512)
        val full = compo.mergedWith(merged)
        assertEquals(70.5, full.weightKg!!, 0.0)
        assertTrue(full.weightStable) // composition frame never flips stability
        assertEquals(512, full.impedanceOhm)

        // A fresh weight frame overrides weight and stability, keeps impedance.
        val reweigh = reading(weightKg = 70.7, weightStable = false).mergedWith(full)
        assertEquals(70.7, reweigh.weightKg!!, 0.0)
        assertFalse(reweigh.weightStable)
        assertEquals(512, reweigh.impedanceOhm)
    }

    // ─── Save guard ───

    @Test
    fun `save needs positive impedance and an averaged weight`() {
        assertFalse(shouldSaveWeighIn(0, 70.5))
        assertFalse(shouldSaveWeighIn(-3, 70.5))
        assertFalse(shouldSaveWeighIn(512, null))
        assertTrue(shouldSaveWeighIn(512, 70.5))
    }
}
