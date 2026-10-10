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
        assertEquals("Increase weight", pickerButtonDescription(increase = true, fieldName = "weight"))
        assertEquals("Decrease reps", pickerButtonDescription(increase = false, fieldName = "reps"))
    }

    @Test
    fun `blank field falls back to generic value`() {
        assertEquals("Increase value", pickerButtonDescription(increase = true, fieldName = ""))
        assertEquals("Decrease value", pickerButtonDescription(increase = false, fieldName = "  "))
    }
}
