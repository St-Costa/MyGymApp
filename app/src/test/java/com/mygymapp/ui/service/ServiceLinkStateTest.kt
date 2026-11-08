package com.mygymapp.ui.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the explicit service mirror (ISM-14): [ServiceLinkState.onAction] and
 * [serviceNotificationTitle]. Each row mirrors one `onStartCommand` branch —
 * the reducer must reproduce the old field assignments exactly, and the title
 * priority must stay `reconnecting > standby > HR > connecting`.
 */
class ServiceLinkStateTest {

    @Test
    fun `START clears everything`() {
        val dirty = ServiceLinkState(reconnecting = true, standby = true, currentHr = 120)
        assertEquals(ServiceLinkState(), dirty.onAction(ServiceLinkAction.START))
    }

    @Test
    fun `UPDATE_HR shows the sample and clears the flags`() {
        val state = ServiceLinkState(reconnecting = true).onAction(ServiceLinkAction.UPDATE_HR, 87)
        assertEquals(87, state.currentHr)
        assertEquals(ServiceLinkState(currentHr = 87), state)
    }

    @Test
    fun `RECONNECTING and STANDBY clear each other and the sample`() {
        assertEquals(
            ServiceLinkState(reconnecting = true),
            ServiceLinkState(currentHr = 90).onAction(ServiceLinkAction.RECONNECTING),
        )
        assertEquals(
            ServiceLinkState(standby = true),
            ServiceLinkState(reconnecting = true, currentHr = 90).onAction(ServiceLinkAction.STANDBY),
        )
    }

    @Test
    fun `STOP leaves the mirror untouched`() {
        val state = ServiceLinkState(standby = true, currentHr = 70)
        assertEquals(state, state.onAction(ServiceLinkAction.STOP))
    }

    @Test
    fun `title priority is reconnecting over standby over HR over connecting`() {
        assertEquals("Connecting…", serviceNotificationTitle(ServiceLinkState()))
        assertEquals("72 BPM", serviceNotificationTitle(ServiceLinkState(currentHr = 72)))
        assertEquals(
            "Standby · stream paused",
            serviceNotificationTitle(ServiceLinkState(standby = true, currentHr = 72)),
        )
        assertEquals(
            "Reconnecting…",
            serviceNotificationTitle(ServiceLinkState(reconnecting = true, standby = true, currentHr = 72)),
        )
    }

    @Test
    fun `fresh state carries no sample`() {
        assertNull(ServiceLinkState().currentHr)
    }
}
