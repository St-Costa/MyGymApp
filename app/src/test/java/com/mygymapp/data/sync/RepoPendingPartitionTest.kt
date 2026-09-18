package com.mygymapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision coverage for [partitionRepoPending] — the `RepoSyncWorker` rule that retires
 * an `upsert` whose file vanished between enqueue and drain (a `delete` tombstone is
 * queued separately), while `delete` ops always send. Pure function: no WorkManager,
 * no disk, no Context.
 */
class RepoPendingPartitionTest {

    private fun upsert(rel: String) = RepoLedgerEntry(relPath = rel, op = "upsert")
    private fun delete(rel: String) = RepoLedgerEntry(relPath = rel, op = "delete")

    @Test
    fun `empty input partitions empty`() {
        val (toSend, stale) = partitionRepoPending(emptyList()) { true }
        assertTrue(toSend.isEmpty())
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `existing upserts all send, nothing retired`() {
        val pending = listOf(upsert("exercises/a.md"), upsert("routines/b.md"))
        val (toSend, stale) = partitionRepoPending(pending) { true }
        assertEquals(pending, toSend)
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `missing upsert is retired, never sent`() {
        val pending = listOf(upsert("exercises/gone.md"), upsert("exercises/here.md"))
        val (toSend, stale) = partitionRepoPending(pending) { it != "exercises/gone.md" }
        assertEquals(listOf("exercises/here.md"), toSend.map { it.relPath })
        assertEquals(listOf("exercises/gone.md"), stale)
    }

    @Test
    fun `deletes always send even with no file on disk`() {
        val pending = listOf(delete("exercises/gone.md"), delete("routines/old.md"))
        val (toSend, stale) = partitionRepoPending(pending) { false }
        assertEquals(2, toSend.size)
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `order is preserved on both sides`() {
        val pending = listOf(
            upsert("a.md"), delete("b.md"), upsert("c.md"), upsert("d.md"),
        )
        val (toSend, stale) = partitionRepoPending(pending) { it != "c.md" }
        assertEquals(listOf("a.md", "b.md", "d.md"), toSend.map { it.relPath })
        assertEquals(listOf("c.md"), stale)
    }
}
