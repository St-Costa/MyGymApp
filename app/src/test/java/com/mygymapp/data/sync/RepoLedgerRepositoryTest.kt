package com.mygymapp.data.sync

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round-trip + state-machine coverage for [RepoLedgerRepository] — the fifth sync pipeline's
 * ledger (`_sync/repo_state.yml`, docs/BACKUP.md §3.1). Exercises the incremental-push
 * no-op, the rename tombstone, delete/upsert ordering, and YAML persistence of a
 * slash-and-dash-bearing relPath key.
 */
class RepoLedgerRepositoryTest {

    private lateinit var tmp: File
    private lateinit var ledger: RepoLedgerRepository

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("repo-ledger-test").toFile()
        ledger = RepoLedgerRepository(FileManager(tmp))
    }

    private fun bytes(s: String) = s.toByteArray()

    @Test
    fun `requeueIfChanged enqueues a new path as PENDING upsert`() = runBlocking {
        ledger.requeueIfChanged("exercises/bench-press-ex-a1b2c3d4.md", bytes("v1"))

        val all = ledger.getAll()
        assertEquals(1, all.size)
        val e = all.single()
        assertEquals("exercises/bench-press-ex-a1b2c3d4.md", e.relPath)
        assertEquals("upsert", e.op)
        assertEquals(SyncStatus.PENDING, e.status)
    }

    @Test
    fun `requeueIfChanged is a no-op once the exact content is SENT`() = runBlocking {
        val path = "routines/pull-rt-71284f58.md"
        ledger.requeueIfChanged(path, bytes("same"))
        ledger.markSent(path)

        ledger.requeueIfChanged(path, bytes("same"))

        assertEquals(SyncStatus.SENT, ledger.getAll().single().status)
        assertTrue(ledger.getPending().isEmpty())
    }

    @Test
    fun `requeueIfChanged re-queues when SENT content then changes`() = runBlocking {
        val path = "exercises/squat-ex-11112222.md"
        ledger.requeueIfChanged(path, bytes("v1"))
        ledger.markSent(path)

        ledger.requeueIfChanged(path, bytes("v2"))

        val e = ledger.getAll().single()
        assertEquals(SyncStatus.PENDING, e.status)
        assertEquals(ledger.hashOf(bytes("v2")), e.contentHash)
    }

    @Test
    fun `markDeleted tombstones a path and keeps it in the ledger`() = runBlocking {
        val path = "exercises/old-typo-ex-deadbeef.md"
        ledger.markDeleted(path, "sha256:oldhash")

        val e = ledger.getAll().single()
        assertEquals("delete", e.op)
        assertEquals(SyncStatus.DELETED_PENDING, e.status)
        assertEquals("sha256:oldhash", e.contentHash)
        assertTrue(ledger.getPending().any { it.relPath == path })
    }

    @Test
    fun `markDeleted then markSent moves to DELETED_SENT and out of pending`() = runBlocking {
        val path = "routines/gone-rt-99998888.md"
        ledger.markDeleted(path, "sha256:h")
        ledger.markSent(path)

        assertEquals(SyncStatus.DELETED_SENT, ledger.getAll().single().status)
        assertTrue(ledger.getPending().isEmpty())
    }

    @Test
    fun `rename produces one upsert for the new path and one tombstone for the old`() = runBlocking {
        // ExerciseRepository.save() on a rename: new file written, old file deleted.
        ledger.requeueIfChanged("exercises/new-name-ex-abcd1234.md", bytes("body"))
        ledger.markDeleted("exercises/old-name-ex-abcd1234.md", "sha256:oldbody")

        val pending = ledger.getPending().associateBy { it.relPath }
        assertEquals("upsert", pending.getValue("exercises/new-name-ex-abcd1234.md").op)
        assertEquals("delete", pending.getValue("exercises/old-name-ex-abcd1234.md").op)
    }

    @Test
    fun `failed upsert becomes FAILED but stays retryable`() = runBlocking {
        val path = "exercises/x-ex-00001111.md"
        ledger.requeueIfChanged(path, bytes("v1"))
        ledger.markFailed(path, "HTTP 500: boom\nstacktrace line")

        val e = ledger.getAll().single()
        assertEquals(SyncStatus.FAILED, e.status)
        assertTrue("newlines stripped from error", !e.lastError.contains("\n"))
        assertTrue(ledger.getPending().any { it.relPath == path })
    }

    @Test
    fun `failed delete stays DELETED_PENDING`() = runBlocking {
        val path = "routines/r-rt-22223333.md"
        ledger.markDeleted(path, "sha256:h")
        ledger.markFailed(path, "network")

        assertEquals(SyncStatus.DELETED_PENDING, ledger.getAll().single().status)
    }

    @Test
    fun `ledger round-trips through YAML with a slash-and-dash relPath key`() = runBlocking {
        val path = "exercises/incline-db-press-ex-0f1e2d3c.md"
        ledger.requeueIfChanged(path, bytes("payload"))
        ledger.markFailed(path, "HTTP 422")

        // Fresh instance over the same dir — forces a real file read.
        val reopened = RepoLedgerRepository(FileManager(tmp))
        val e = reopened.getAll().single()
        assertEquals(path, e.relPath)
        assertEquals(SyncStatus.FAILED, e.status)
        assertEquals("upsert", e.op)
        assertEquals(1, e.attempts)
    }

    @Test
    fun `markRestored records the server hash as already-SENT`() = runBlocking {
        val path = "exercises/from-server-ex-44445555.md"
        ledger.markRestored(path, "sha256:serverhash")

        val e = ledger.getAll().single()
        assertEquals(SyncStatus.SENT, e.status)
        assertEquals("sha256:serverhash", e.contentHash)
        assertNull(ledger.getPending().firstOrNull { it.relPath == path })
    }
}
