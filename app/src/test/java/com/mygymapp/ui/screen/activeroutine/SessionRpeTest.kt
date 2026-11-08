package com.mygymapp.ui.screen.activeroutine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the session-RPE gate (second sweep, ISM-01 remainder): 0..9 accepted,
 * anything else keeps the mandatory prompt open.
 */
class SessionRpeTest {

    @Test
    fun `ratings 0 through 9 are valid, everything else is not`() {
        for (rpe in 0..9) assertTrue("rpe=$rpe", isValidSessionRpe(rpe))
        assertFalse(isValidSessionRpe(-1))
        assertFalse(isValidSessionRpe(10))
        assertFalse(isValidSessionRpe(Int.MIN_VALUE))
        assertFalse(isValidSessionRpe(Int.MAX_VALUE))
    }
}
