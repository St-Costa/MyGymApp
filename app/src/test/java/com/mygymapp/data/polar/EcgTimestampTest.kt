package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 2 regression: the `.ecg` header timestamp must be wall-clock ns-since-epoch
 * (the server derives cardio-block ranges from `startTimestamp + sampleRate`), never
 * `System.nanoTime()` (monotonic-since-boot — an arbitrary offset per recording).
 */
class EcgTimestampTest {

    @Test
    fun `epochTimestampNs is now in milliseconds scaled to nanoseconds`() {
        val nowMs = System.currentTimeMillis()
        val ts = EcgRecorder.epochTimestampNs(nowMs)

        assertEquals(nowMs * 1_000_000L, ts)
        // Sanity: lands in the wall-clock domain, not the nanoTime domain (boot-relative,
        // orders of magnitude smaller on a phone up for days).
        assertTrue(ts > 1_000_000_000_000_000_000L) // Sept 2001 in ns — any real now is past it
        assertTrue(ts / 1_000_000L in (nowMs - 60_000)..(nowMs + 60_000))
    }

    @Test
    fun `epochTimestampNs default uses the current wall clock`() {
        val before = System.currentTimeMillis()
        val ts = EcgRecorder.epochTimestampNs()
        val after = System.currentTimeMillis()

        assertTrue(ts / 1_000_000L in before..after)
    }

    @Test
    fun `epochTimestampNs grows monotonically with wall time`() {
        assertTrue(EcgRecorder.epochTimestampNs(1_000L) < EcgRecorder.epochTimestampNs(2_000L))
    }
}
