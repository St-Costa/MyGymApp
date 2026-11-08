package com.mygymapp.ui.screen.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure Options decisions (ISM-26): [resyncProgress], [ecgDebugGate]
 * and [polarSelfTestLiveSummary].
 */
class OptionsOpsTest {

    // ─── Resync progress ───

    @Test
    fun `progress is drained over total, clamped both ways`() {
        assertEquals(0f, resyncProgress(10, 10))
        assertEquals(0.5f, resyncProgress(10, 5))
        assertEquals(1f, resyncProgress(10, 0))
        // Concurrently queued sessions can't push the bar backwards or past full.
        assertEquals(0f, resyncProgress(10, 12))
        assertEquals(1f, resyncProgress(10, -3))
    }

    @Test
    fun `empty batch is trivially complete`() {
        assertEquals(1f, resyncProgress(0, 0))
    }

    // ─── ECG debug gate ───

    @Test
    fun `debug recording needs a live strap first, then a server`() {
        assertEquals(EcgDebugGate.NO_POLAR, ecgDebugGate(false, false))
        assertEquals(EcgDebugGate.NO_POLAR, ecgDebugGate(false, true))
        assertEquals(EcgDebugGate.NO_SERVER, ecgDebugGate(true, false))
        assertEquals(EcgDebugGate.READY, ecgDebugGate(true, true))
    }

    // ─── Self-test summary ───

    @Test
    fun `silent strap is reported as silent, not as zero`() {
        assertTrue(
            polarSelfTestLiveSummary(emptyList(), 60).contains("silent"),
        )
    }

    @Test
    fun `samples summarize count avg min max`() {
        assertEquals(
            "Live: 3 samples, avg 100, min 90, max 110",
            polarSelfTestLiveSummary(listOf(90, 100, 110), 60),
        )
    }

    @Test
    fun `self-test label names the phase`() {
        assertEquals("SEARCHING… 30S", polarSelfTestLabel(running = true, searching = true, secondsLeft = 30))
        assertEquals("TESTING… 60S", polarSelfTestLabel(running = true, searching = false, secondsLeft = 60))
        assertEquals("POLAR SELF-TEST", polarSelfTestLabel(running = false, searching = false, secondsLeft = 0))
    }
}
