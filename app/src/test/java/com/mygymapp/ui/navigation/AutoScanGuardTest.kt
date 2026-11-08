package com.mygymapp.ui.navigation

import com.mygymapp.data.polar.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the auto-scan gate (second sweep): scan only with permissions, a known
 * strap, a down link and no scan running — never tearing down a live link.
 */
class AutoScanGuardTest {

    @Test
    fun `all four conditions required`() {
        assertTrue(shouldAutoScan(true, true, isDisconnected = true, isScanning = false))
        assertFalse(shouldAutoScan(false, true, isDisconnected = true, isScanning = false))
        assertFalse(shouldAutoScan(true, false, isDisconnected = true, isScanning = false))
        assertFalse(shouldAutoScan(true, true, isDisconnected = false, isScanning = false))
        assertFalse(shouldAutoScan(true, true, isDisconnected = true, isScanning = true))
    }

    @Test
    fun `the gate mirrors the connection states`() {
        // Only DISCONNECTED scans; anything else (incl. a mid-handshake CONNECTING) holds.
        for (state in ConnectionState.entries) {
            val expected = state == ConnectionState.DISCONNECTED
            val actual = shouldAutoScan(true, true, state == ConnectionState.DISCONNECTED, isScanning = false)
            assertTrue("state=$state", expected == actual)
        }
    }
}
