package com.mygymapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 3: the stepper TalkBack labels. (The semantics wiring itself needs a Compose
 * harness, which this repo doesn't have — this pins the strings the buttons announce.)
 */
class PickerButtonDescriptionTest {

    @Test
    fun `increase and decrease with field name`() {
        assertEquals("Aumenta peso", pickerButtonDescription(increase = true, fieldName = "peso"))
        assertEquals("Diminuisci ripetizioni", pickerButtonDescription(increase = false, fieldName = "ripetizioni"))
    }

    @Test
    fun `blank field falls back to generic valore`() {
        assertEquals("Aumenta valore", pickerButtonDescription(increase = true, fieldName = ""))
        assertEquals("Diminuisci valore", pickerButtonDescription(increase = false, fieldName = "  "))
    }
}
