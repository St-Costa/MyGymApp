package com.mygymapp.data.scale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [ScaleWeightAverager]: the tail average that turns a connection's raw
 * weight notify samples into the saved weigh-in weight in [BleScaleManager].
 */
class ScaleWeightAveragerTest {

    @Test
    fun `empty sample list returns null`() {
        assertNull(ScaleWeightAverager.average(emptyList()))
    }

    @Test
    fun `fewer samples than the window averages all of them`() {
        val samples = listOf(79.8, 80.0, 80.2)

        assertEquals(80.0, ScaleWeightAverager.average(samples)!!, 1e-9)
    }

    @Test
    fun `ramp-up head does not pollute the tail average`() {
        // Reproduces the reported 0.4 kg case: stepping on + moving around fills the
        // head with low/erratic samples, then the weight settles at 80.0 kg.
        val head = listOf(41.2, 63.7, 74.9, 78.3, 79.1, 79.9, 80.4, 79.6, 80.3, 79.8) +
            List(20) { i -> 79.5 + (i % 5) * 0.1 } // restless mid-session
        val stableTail = List(ScaleWeightAverager.TAIL_WINDOW) { 80.0 }

        val result = ScaleWeightAverager.average(head + stableTail)!!

        assertEquals(80.0, result, 1e-9)
    }

    @Test
    fun `exactly window-sized list averages the whole list`() {
        val samples = List(ScaleWeightAverager.TAIL_WINDOW) { 75.0 }

        assertEquals(75.0, ScaleWeightAverager.average(samples)!!, 1e-9)
    }

    @Test
    fun `same-connection re-weigh falls inside the window and wins`() {
        // First weigh-in settles at 80 kg, user stays on and the scale restarts the
        // measurement settling at 80.5 kg — the tail must reflect only the second one.
        val firstWeighIn = List(30) { 80.0 }
        val secondWeighIn = List(ScaleWeightAverager.TAIL_WINDOW) { 80.5 }

        val result = ScaleWeightAverager.average(firstWeighIn + secondWeighIn)!!

        assertEquals(80.5, result, 1e-9)
    }
}
