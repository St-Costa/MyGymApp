package com.mygymapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 7: beep-volume bounds. (The SharedPreferences read/write itself needs a
 * Context, so only the pure coercion is pinned here.)
 */
class BeepVolumeTest {

    @Test
    fun `in-range values pass through`() {
        assertEquals(0, BeepVolumeRepository.coerceBeepVolume(0))
        assertEquals(63, BeepVolumeRepository.coerceBeepVolume(63))
        assertEquals(100, BeepVolumeRepository.coerceBeepVolume(100))
    }

    @Test
    fun `out-of-range values clamp`() {
        assertEquals(0, BeepVolumeRepository.coerceBeepVolume(-1))
        assertEquals(100, BeepVolumeRepository.coerceBeepVolume(101))
        assertEquals(100, BeepVolumeRepository.coerceBeepVolume(Int.MAX_VALUE))
    }

    @Test
    fun `default is full volume`() {
        assertEquals(100, BeepVolumeRepository.DEFAULT_BEEP_VOLUME)
    }
}
