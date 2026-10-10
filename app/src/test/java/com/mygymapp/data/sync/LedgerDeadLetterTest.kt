package com.mygymapp.data.sync

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sprint 8: entries whose source file is gone for good are dead-lettered (EXPIRED, out
 * of `getPending()`), not retried every 4h forever. Covers the session, readiness and
 * scale ledgers (the ECG one is pinned by `EcgLedgerDeadTest`).
 */
class LedgerDeadLetterTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("ledger-dead-test").toFile()
    }

    private fun sessionFile(): File = File(tmp, "s.md").apply { writeText("body") }

    @Test
    fun `session ledger dead-letters missing files`() = runBlocking {
        val ledger = SyncLedgerRepository(FileManager(tmp))
        ledger.enqueue("abcd1234", "2026/10/f.md", sessionFile())
        assertEquals(1, ledger.getPending().size)

        ledger.markDead("abcd1234", "session file not found on disk")

        assertTrue(ledger.getPending().isEmpty())
        assertEquals(SyncStatus.EXPIRED, ledger.getAll().single().status)
    }

    @Test
    fun `readiness ledger dead-letters missing files`() = runBlocking {
        val ledger = ReadinessLedgerRepository(FileManager(tmp))
        ledger.enqueue("reeady01", "readiness/reeady01.md", sessionFile())

        ledger.markDead("reeady01", "readiness file not found on disk")

        assertTrue(ledger.getPending().isEmpty())
        assertEquals(SyncStatus.EXPIRED, ledger.getAll().single().status)
    }

    @Test
    fun `scale ledger dead-letters missing files`() = runBlocking {
        val ledger = ScaleWeighInLedgerRepository(FileManager(tmp))
        ledger.enqueue("2026-10-10", "2026/10/2026-10-10.md", sessionFile())

        ledger.markDead("2026-10-10", "weigh-in file not found on disk")

        assertTrue(ledger.getPending().isEmpty())
        assertEquals(SyncStatus.EXPIRED, ledger.getAll().single().status)
    }

    @Test
    fun `markDead on unknown ids is a no-op`() = runBlocking {
        SyncLedgerRepository(FileManager(tmp)).markDead("nope", "x")
        ReadinessLedgerRepository(FileManager(tmp)).markDead("nope", "x")
        ScaleWeighInLedgerRepository(FileManager(tmp)).markDead("nope", "x")
        // No crash, nothing recorded.
        assertTrue(SyncLedgerRepository(FileManager(tmp)).getAll().isEmpty())
    }

    @Test
    fun `FAILED stays retryable after the dead-letter change`() = runBlocking {
        val ledger = SyncLedgerRepository(FileManager(tmp))
        ledger.enqueue("abcd1234", "2026/10/f.md", sessionFile())
        ledger.markFailed("abcd1234", "HTTP 500")

        assertEquals(SyncStatus.FAILED, ledger.getPending().single().status)
    }
}
