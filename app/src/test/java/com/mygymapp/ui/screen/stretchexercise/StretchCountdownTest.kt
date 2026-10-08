package com.mygymapp.ui.screen.stretchexercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the [StretchCountdown] loop math behind the stretch timer
 * (see docs/CONVENTIONS.md — stretch countdown section).
 */
class StretchCountdownTest {

    @Test
    fun `start begins at full duration on loop 1`() {
        val state = StretchCountdown.start(60)
        assertEquals(60, state.remainingSeconds)
        assertEquals(1, state.loopCount)
    }

    @Test
    fun `start coerces non-positive duration to 1`() {
        assertEquals(1, StretchCountdown.start(0).remainingSeconds)
        assertEquals(1, StretchCountdown.start(-30).remainingSeconds)
    }

    @Test
    fun `tick decrements without completing the loop`() {
        val (next, loopCompleted) = StretchCountdown.start(60).tick(60)
        assertEquals(59, next.remainingSeconds)
        assertEquals(1, next.loopCount)
        assertFalse(loopCompleted)
    }

    @Test
    fun `tick on last second wraps to full duration and bumps the loop`() {
        val last = StretchCountdown(remainingSeconds = 1, loopCount = 2)
        val (next, loopCompleted) = last.tick(60)
        assertEquals(60, next.remainingSeconds)
        assertEquals(3, next.loopCount)
        assertTrue(loopCompleted)
    }

    @Test
    fun `full 3-second loop wraps exactly once`() {
        var state = StretchCountdown.start(3)
        var completions = 0
        repeat(7) {
            val (next, loopCompleted) = state.tick(3)
            state = next
            if (loopCompleted) completions++
        }
        // 3,2,1 | 3,2,1 | 3 → two wraps, remaining 2 of loop 3
        assertEquals(2, completions)
        assertEquals(2, state.remainingSeconds)
        assertEquals(3, state.loopCount)
    }

    @Test
    fun `tick with non-positive duration falls back to 1`() {
        val (next, loopCompleted) = StretchCountdown(remainingSeconds = 1, loopCount = 1).tick(0)
        assertEquals(1, next.remainingSeconds)
        assertEquals(2, next.loopCount)
        assertTrue(loopCompleted)
    }
}
