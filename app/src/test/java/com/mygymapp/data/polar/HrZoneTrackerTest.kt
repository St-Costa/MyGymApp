package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the zone gap veto (second sweep): first tick anchors, plausible gaps
 * attribute to the leading zone, reconnect/background gaps are dropped.
 */
class HrZoneTrackerTest {

    @Test
    fun `gap veto drops missing anchors and implausible gaps`() {
        assertNull(zoneGapMinutes(0L, 60_000L))
        assertEquals(0.1, zoneGapMinutes(1L, 6_001L)!!, 1e-9)
        assertNull(zoneGapMinutes(1_000L, 1_000L + 13_000L)) // 13s > 0.2min
        assertNull(zoneGapMinutes(1_000L, 500L)) // backward clock
    }

    @Test
    fun `onTick attributes plausible gaps to the leading zone`() {
        val tracker = HrZoneTracker()
        tracker.onTick(HrZone.Z2, nowMs = 1_000L) // anchor, nothing attributed
        assertEquals(0.0, tracker.current.total, 0.0)
        tracker.onTick(HrZone.Z3, nowMs = 7_000L) // 6s in Z2 = 0.1min
        assertEquals(0.1, tracker.current.zone2, 1e-9)
        assertEquals(0.0, tracker.current.zone3, 1e-9)
    }

    @Test
    fun `onTick drops reconnect gaps and resets cleanly`() {
        val tracker = HrZoneTracker()
        tracker.onTick(HrZone.Z2, nowMs = 1_000L)
        tracker.onTick(HrZone.Z2, nowMs = 3_601_000L) // 1h gap: dropped
        assertEquals(0.0, tracker.current.total, 0.0)
        tracker.reset()
        tracker.onTick(HrZone.Z1, nowMs = 5_000L)
        assertEquals(0.0, tracker.current.total, 0.0)
    }
}
