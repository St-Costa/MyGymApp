package com.mygymapp.ui.screen.activeroutine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveRoutineBadgesTest {

    @Test
    fun `tonnageChangePct computes signed percent change`() {
        assertEquals(25.0, tonnageChangePct(2500.0, 2000.0)!!, 0.001)
        assertEquals(-50.0, tonnageChangePct(1000.0, 2000.0)!!, 0.001)
        assertEquals(0.0, tonnageChangePct(2000.0, 2000.0)!!, 0.001)
    }

    @Test
    fun `tonnageChangePct returns null without a comparable baseline`() {
        // Primo dato: nessun baseline -> niente badge, la riga mostra "primo dato".
        assertNull(tonnageChangePct(2500.0, null))
        // Baseline a zero (mai tonnellaggio reale) -> divisione evitata.
        assertNull(tonnageChangePct(2500.0, 0.0))
    }

    @Test
    fun `rmChangePct computes signed percent change`() {
        assertEquals(10.0, rmChangePct(110.0, 100.0)!!, 0.001)
        assertEquals(-10.0, rmChangePct(90.0, 100.0)!!, 0.001)
    }

    @Test
    fun `rmChangePct returns null when either side is missing`() {
        assertNull(rmChangePct(null, 100.0))
        assertNull(rmChangePct(110.0, null))
        assertNull(rmChangePct(110.0, 0.0))
    }

    @Test
    fun `resolveSwitchBaseline prefers the replaced exercise history`() {
        // Il badge del subentrato si confronta col sostituito, anche quando il subentrato
        // ha un proprio storico (che viene ignorato).
        val baseline = resolveSwitchBaseline(
            replacedTonnage = 2000.0,
            replacedBestE1RM = 100.0,
            ownTonnage = 5000.0,
            ownBestE1RM = 150.0,
        )
        assertEquals(2000.0, baseline.tonnage!!, 0.001)
        assertEquals(100.0, baseline.bestE1RM!!, 0.001)
    }

    @Test
    fun `resolveSwitchBaseline falls back to own history without replaced history`() {
        val baseline = resolveSwitchBaseline(
            replacedTonnage = null,
            replacedBestE1RM = null,
            ownTonnage = 5000.0,
            ownBestE1RM = 150.0,
        )
        assertEquals(5000.0, baseline.tonnage!!, 0.001)
        assertEquals(150.0, baseline.bestE1RM!!, 0.001)
    }

    @Test
    fun `resolveSwitchBaseline is empty when neither has history`() {
        // Nessun riferimento: la riga mostra "primo dato".
        val baseline = resolveSwitchBaseline(
            replacedTonnage = null,
            replacedBestE1RM = null,
            ownTonnage = null,
            ownBestE1RM = null,
        )
        assertNull(baseline.tonnage)
        assertNull(baseline.bestE1RM)
    }

    @Test
    fun `switch badge compares completed work against the replaced exercise`() {
        // 2500 kg col nuovo esercizio contro 2000 kg dell'ultima volta del sostituito.
        val baseline = resolveSwitchBaseline(
            replacedTonnage = 2000.0,
            replacedBestE1RM = 100.0,
            ownTonnage = null,
            ownBestE1RM = null,
        )
        assertEquals(25.0, tonnageChangePct(2500.0, baseline.tonnage)!!, 0.001)
        assertEquals(10.0, rmChangePct(110.0, baseline.bestE1RM)!!, 0.001)
    }
}
