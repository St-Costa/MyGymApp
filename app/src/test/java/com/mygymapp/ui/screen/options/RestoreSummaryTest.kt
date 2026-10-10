package com.mygymapp.ui.screen.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 8: restore reports overwritten files separately (their local bytes are stashed
 * under `_conflicts/` first), instead of silently counting them as plain restores.
 */
class RestoreSummaryTest {

    @Test
    fun `clean restore has no overwrite or failure suffix`() {
        assertEquals(
            "Restored 12 exercises, 3 routines",
            buildRestoreSummary(12, 3, 0, 0, 0),
        )
    }

    @Test
    fun `other overwritten and failed all render`() {
        assertEquals(
            "Restored 12 exercises, 3 routines, 2 other files, " +
                "1 overwritten (local copy in _conflicts/) (2 failed)",
            buildRestoreSummary(12, 3, 2, 1, 2),
        )
    }

    @Test
    fun `conflict stash path nests the relPath under a timestamp`() {
        val path = conflictBackupRelPath("exercises/bench-ex-a1b2c3d4.md", "20261010-120000")

        assertEquals("_conflicts/20261010-120000/exercises/bench-ex-a1b2c3d4.md", path)
        assertTrue(path.startsWith("_conflicts/"))
    }
}
