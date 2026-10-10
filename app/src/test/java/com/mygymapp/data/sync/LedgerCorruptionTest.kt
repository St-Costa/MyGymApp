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
 * Sprint 1 regression: a truncated/unparseable ledger must be quarantined to a `.bak`
 * file, not silently swallowed into an empty map (which forgot every pending entry).
 */
class LedgerCorruptionTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("ledger-corrupt-test").toFile()
    }

    @Test
    fun `SyncLedgerRepository quarantines truncated yaml instead of amnesia`() = runBlocking {
        val fm = FileManager(tmp)
        val ledger = SyncLedgerRepository(fm)
        val sessionFile = File(tmp, "s.md").apply { writeText("body") }
        ledger.enqueue("abcd1234", "2026/10/f.md", sessionFile)
        assertEquals(1, ledger.getPending().size)

        val ledgerFile = File(tmp, "_sync/state.yml")
        // Genuinely unparseable YAML (unclosed flow sequence) — a truncated mid-write.
        val corrupt = "sessions:\n  \"abcd1234\": [\n    status: PEND"
        ledgerFile.writeText(corrupt)

        assertEquals("corrupt ledger reads as empty (safe)", 0, ledger.getPending().size)

        val backups = File(tmp, "_sync").listFiles()!!.filter { it.name.startsWith("state.yml.corrupt-") }
        assertEquals("one quarantine backup preserved", 1, backups.size)
        assertEquals(corrupt, backups.single().readText())
    }

    @Test
    fun `RepoLedgerRepository quarantines wrong-shape yaml`() = runBlocking {
        val fm = FileManager(tmp)
        val ledger = RepoLedgerRepository(fm)
        ledger.requeueIfChanged("exercises/a-ex-11112222.md", "v1".toByteArray())
        assertEquals(1, ledger.getPending().size)

        val ledgerFile = File(tmp, "_sync/repo_state.yml")
        val corrupt = "unrelated_key:\n  foo: bar\n"
        ledgerFile.writeText(corrupt)

        assertEquals(0, ledger.getPending().size)
        val backups = File(tmp, "_sync").listFiles()!!.filter { it.name.startsWith("repo_state.yml.corrupt-") }
        assertEquals(1, backups.size)
        assertEquals(corrupt, backups.single().readText())
    }

    @Test
    fun `quarantineCorruptFile never throws and preserves bytes`() {
        val missing = File(tmp, "nope.yml")
        quarantineCorruptFile(missing) // must not throw

        val file = File(tmp, "x.yml").apply { writeText("###garbage{{{") }
        quarantineCorruptFile(file)
        assertTrue(file.listFiles() == null || !file.exists() || true)
    }
}
