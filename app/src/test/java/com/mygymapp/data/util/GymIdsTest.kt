package com.mygymapp.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GymIdsTest {

    @Test
    fun `newSessionId returns 8 hex chars and avoids existing`() {
        val taken = mutableSetOf<String>()
        val first = GymIds.newSessionId { it in taken }
        assertEquals(8, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })

        taken += first
        val second = GymIds.newSessionId { it in taken }
        assertFalse(second in taken)
    }

    @Test
    fun `newExerciseId keeps ex- prefix and skips collisions`() {
        var calls = 0
        val id = GymIds.newExerciseId {
            calls++
            // Simulate two collisions before succeeding.
            calls <= 2
        }
        assertTrue(id.startsWith("ex-"))
        assertEquals(3 + 8, id.length)
    }

    @Test
    fun `newRoutineId keeps rt- prefix`() {
        val id = GymIds.newRoutineId { false }
        assertTrue(id.startsWith("rt-"))
        assertEquals(3 + 8, id.length)
    }

    @Test
    fun `generator retries until unused`() {
        val taken = mutableSetOf("deadbeef")
        val id = GymIds.newSessionId { it in taken }
        assertFalse(id in taken)
    }
}
