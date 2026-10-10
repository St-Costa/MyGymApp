package com.mygymapp.data.polar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrNotificationThrottleTest {

    @Test
    fun `first sample always sends`() {
        val t = HrNotificationThrottle()
        assertTrue(t.shouldSend(120, 0L))
    }

    @Test
    fun `same bpm never re-sends`() {
        val t = HrNotificationThrottle()
        assertTrue(t.shouldSend(120, 0L))
        assertFalse(t.shouldSend(120, 1_000L))
        assertFalse(t.shouldSend(120, 60_000L))
    }

    @Test
    fun `small change within interval is suppressed, after interval it sends`() {
        val t = HrNotificationThrottle()
        assertTrue(t.shouldSend(120, 0L))
        assertFalse(t.shouldSend(121, 2_000L))
        assertTrue(t.shouldSend(121, 5_000L))
    }

    @Test
    fun `large jump forces an immediate send`() {
        val t = HrNotificationThrottle()
        assertTrue(t.shouldSend(120, 0L))
        assertTrue(t.shouldSend(150, 1_000L))
    }

    @Test
    fun `just under the force delta stays suppressed`() {
        val t = HrNotificationThrottle()
        assertTrue(t.shouldSend(120, 0L))
        assertFalse(t.shouldSend(123, 1_000L))
    }
}
