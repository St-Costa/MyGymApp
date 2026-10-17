package com.mygymapp.ui.screen.superset

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [supersetMemberPerformed], the performed-work decision behind "Complete
 * Superset" (see docs/CONVENTIONS.md "Re-entering a skipped exercise").
 */
class SupersetMemberPerformedTest {

    @Test
    fun `touching any value makes the member performed, whatever the entry state`() {
        assertTrue(
            supersetMemberPerformed(anyTouched = true, entryCompleted = false, entryCompletedEmpty = false)
        )
        assertTrue(
            supersetMemberPerformed(anyTouched = true, entryCompleted = true, entryCompletedEmpty = true)
        )
        assertTrue(
            supersetMemberPerformed(anyTouched = true, entryCompleted = true, entryCompletedEmpty = false)
        )
    }

    @Test
    fun `an untouched member already performed at entry stays performed`() {
        assertTrue(
            supersetMemberPerformed(anyTouched = false, entryCompleted = true, entryCompletedEmpty = false)
        )
    }

    @Test
    fun `an untouched member skipped at entry stays skipped`() {
        assertFalse(
            supersetMemberPerformed(anyTouched = false, entryCompleted = true, entryCompletedEmpty = true)
        )
    }

    @Test
    fun `an untouched member still open at entry closes skipped`() {
        assertFalse(
            supersetMemberPerformed(anyTouched = false, entryCompleted = false, entryCompletedEmpty = false)
        )
    }
}
