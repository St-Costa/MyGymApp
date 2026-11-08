package com.mygymapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the shared verify-box terminal rule (ISM-25/26):
 * [backupVerifyOutcomeBox] sets exactly one of error/report.
 */
class BackupVerifyOutcomeBoxTest {

    @Test
    fun `hard fail surfaces the reason and no report`() {
        val (error, report) = backupVerifyOutcomeBox(
            BackupVerifyOutcome.HardFail("Server sync not configured"),
        )
        assertEquals("Server sync not configured", error)
        assertNull(report)
    }

    @Test
    fun `done surfaces the report and no error`() {
        val expected = BackupVerifyReport(exercisesLocal = 3, exercisesMatching = 3)
        val (error, report) = backupVerifyOutcomeBox(BackupVerifyOutcome.Done(expected))
        assertNull(error)
        assertEquals(expected, report)
    }
}
