package com.mygymapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [RepoSyncApi.chunkForBulk] — splits a ledger drain so no `POST /v1/repo/bulk` request
 * exceeds the server's 500-entry / 50-MB cap (`docs/backup/README.md` § "Batch endpoints").
 */
class RepoSyncApiChunkTest {

    private val api = RepoSyncApi()

    private fun deleteEntry(i: Int) = RepoBulkEntry("routines/gone-rt-$i.md", "delete", "sha256:x", file = null)

    /** A fake File whose length() returns [bytes] without touching disk. */
    private fun sizedEntry(i: Int, bytes: Long) = RepoBulkEntry(
        "exercises/big-ex-$i.md", "upsert", "sha256:y",
        file = object : File("/nonexistent/big-ex-$i.md") {
            override fun length(): Long = bytes
        },
    )

    @Test
    fun `empty input yields no chunks`() {
        assertEquals(0, api.chunkForBulk(emptyList()).size)
    }

    @Test
    fun `under both caps is a single chunk`() {
        val entries = (1..499).map { deleteEntry(it) }
        val chunks = api.chunkForBulk(entries)
        assertEquals(1, chunks.size)
        assertEquals(499, chunks[0].size)
    }

    @Test
    fun `splits at 500 entries`() {
        val entries = (1..1001).map { deleteEntry(it) }
        val chunks = api.chunkForBulk(entries)
        assertEquals(listOf(500, 500, 1), chunks.map { it.size })
        // every original entry present, order preserved
        assertEquals(entries.map { it.relPath }, chunks.flatten().map { it.relPath })
    }

    @Test
    fun `splits when aggregate file bytes would exceed 50 MB`() {
        val twentyMb = 20L * 1024 * 1024
        val entries = (1..5).map { sizedEntry(it, twentyMb) } // 100 MB total
        val chunks = api.chunkForBulk(entries)
        // 20+20 fits (40 MB), a 3rd (60 MB) overflows → [2,2,1]
        assertEquals(listOf(2, 2, 1), chunks.map { it.size })
    }

    @Test
    fun `a single oversized entry still gets its own chunk rather than being dropped`() {
        val huge = 80L * 1024 * 1024
        val chunks = api.chunkForBulk(listOf(sizedEntry(1, huge)))
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].size)
    }

    @Test
    fun `mixed upserts and deletes preserve interleaved order across a split`() {
        val entries = (1..600).map { if (it % 2 == 0) deleteEntry(it) else sizedEntry(it, 1024) }
        val chunks = api.chunkForBulk(entries)
        assertTrue(chunks.size >= 2)
        assertEquals(entries.map { it.relPath }, chunks.flatten().map { it.relPath })
    }
}
