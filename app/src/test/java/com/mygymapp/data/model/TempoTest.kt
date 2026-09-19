package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoTest {

    @Test
    fun `fromFileString parses 4-0-X-0`() {
        val tempo = Tempo.fromFileString("4-0-X-0")!!
        assertEquals(TempoDuration.Seconds(4), tempo.eccentric)
        assertEquals(TempoDuration.Seconds(0), tempo.pauseBottom)
        assertEquals(TempoDuration.Explosive, tempo.concentric)
        assertEquals(TempoDuration.Seconds(0), tempo.pauseTop)
    }

    @Test
    fun `fromFileString is case-insensitive and accepts lowercase x`() {
        assertEquals(TempoDuration.Explosive, Tempo.fromFileString("3-1-x-0")!!.concentric)
    }

    @Test
    fun `fromFileString rejects wrong arity and out-of-range values`() {
        assertNull(Tempo.fromFileString("4-0-1"))
        assertNull(Tempo.fromFileString("4-0-1-0-2"))
        assertNull(Tempo.fromFileString("6-0-1-0"))
        assertNull(Tempo.fromFileString("4-0-Y-0"))
        assertNull(Tempo.fromFileString(""))
        assertNull(Tempo.fromFileString(null))
    }

    @Test
    fun `all-zero tempo is disabled, anything else is not`() {
        assertTrue(Tempo.disabled().isDisabled)
        assertFalse(Tempo.default().isDisabled)
        assertFalse(Tempo.fromFileString("0-0-X-0")!!.isDisabled)
    }

    @Test
    fun `activePhases skips zeros but keeps X`() {
        val active = Tempo.fromFileString("4-0-X-0")!!.activePhases()
        assertEquals(listOf(0, 2), active.map { it.first })
    }

    @Test
    fun `X animates as 0_35s`() {
        assertEquals(350L, TempoDuration.Explosive.animationDurationMs())
        assertEquals(4000L, TempoDuration.Seconds(4).animationDurationMs())
    }

    @Test
    fun `stepper follows X-0-1-2-3-4-5 with no wrap`() {
        assertEquals(
            TempoDuration.Explosive,
            TempoDuration.Seconds(0).stepped(-1),
        )
        assertEquals(
            "below X is unreachable",
            TempoDuration.Explosive,
            TempoDuration.Explosive.stepped(-1),
        )
        assertEquals(
            TempoDuration.Seconds(0),
            TempoDuration.Explosive.stepped(+1),
        )
        assertEquals(
            "5 is the ceiling",
            TempoDuration.Seconds(5),
            TempoDuration.Seconds(5).stepped(+1),
        )
        assertEquals(
            TempoDuration.Seconds(3),
            TempoDuration.Seconds(2).stepped(+1),
        )
        assertEquals(
            TempoDuration.Seconds(2),
            TempoDuration.Seconds(3).stepped(-1),
        )
    }

    @Test
    fun `toFileString round-trips through fromFileString`() {
        listOf("4-0-1-0", "3-1-X-0", "0-0-0-0", "5-5-5-5", "X-0-1-5").forEach { raw ->
            assertEquals(raw, Tempo.fromFileString(raw)!!.toFileString())
        }
    }
}
