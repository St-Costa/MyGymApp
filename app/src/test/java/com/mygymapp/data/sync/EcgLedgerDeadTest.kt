package com.mygymapp.data.sync

import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.repository.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sprint 2 regression: an ECG ledger entry whose source file can never come back must be
 * dead-lettered ([EcgSyncLedgerRepository.markDead] → EXPIRED, out of `getPending`),
 * not `markFailed` + retried forever on the 4h period.
 */
class EcgLedgerDeadTest {

    private lateinit var tmp: File
    private lateinit var ledger: EcgSyncLedgerRepository

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("ecg-dead-test").toFile()
        ledger = EcgSyncLedgerRepository(FileManager(tmp))
    }

    @Test
    fun `markDead removes from pending and records EXPIRED with reason`() = runBlocking {
        ledger.enqueue("deadbeef", "ecg/deadbeef.ecg", "raw".toByteArray())
        assertEquals(1, ledger.getPending().size)

        ledger.markDead("deadbeef", "ecg file and session not found on disk")

        assertTrue(ledger.getPending().isEmpty())
    }

    @Test
    fun `markDead on unknown id is a no-op`() = runBlocking {
        ledger.markDead("nope1234", "reason")
        assertTrue(ledger.getPending().isEmpty())
    }

    @Test
    fun `markFailed still keeps entries retryable (dead is opt-in, not default)`() = runBlocking {
        ledger.enqueue("cafef00d", "ecg/cafef00d.ecg", "raw".toByteArray())
        ledger.markFailed("cafef00d", "HTTP 500")

        assertEquals(1, ledger.getPending().size)
        assertEquals(SyncStatus.FAILED, ledger.getPending().single().status)
    }

    @Test
    fun `sessionFileExists tracks saved sessions by id without parsing`() = runBlocking {
        val fm = FileManager(tmp)
        val repo = WorkoutRepository(fm, SyncLedgerRepository(fm))

        assertFalse(repo.sessionFileExists("abcd1234"))
        assertFalse(repo.sessionFileExists(""))

        repo.save(
            WorkoutSession(
                id = "abcd1234",
                routineId = "rt-11112222",
                routineName = "R",
                date = "2026-10-01",
                completedAt = "2026-10-01T10:00:00",
            ),
        )

        assertTrue(repo.sessionFileExists("abcd1234"))
        assertFalse(repo.sessionFileExists("ffffffff"))
    }
}
