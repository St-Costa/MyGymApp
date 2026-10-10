package com.mygymapp.data.repository

import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.sync.SyncLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Sprint 1 regression: re-saving a session with a different `date` must MOVE the file,
 * not fork it. The old code derived the path from `session.date` and only ever ADDED to
 * the index, so the stale file survived on disk and in range scans (double tonnage).
 */
class WorkoutRepositoryDateChangeTest {

    private lateinit var tmp: File
    private lateinit var repo: WorkoutRepository

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("workout-date-test").toFile()
        val fm = FileManager(tmp)
        repo = WorkoutRepository(fm, SyncLedgerRepository(fm))
    }

    private fun session(id: String, date: String) = WorkoutSession(
        id = id,
        routineId = "rt-11112222",
        routineName = "Test routine",
        date = date,
        startedAt = "${date}T09:00:00",
        completedAt = "${date}T10:00:00",
        exercises = emptyList(),
    )

    @Test
    fun `re-save with changed date moves the file without duplicating`() = runBlocking {
        repo.save(session("abcd1234", "2026-10-01"))
        val oldFile = File(tmp, "history/2026/10/2026-10-01_rt-11112222_abcd1234.md")
        assertTrue("initial file written", oldFile.exists())

        repo.save(session("abcd1234", "2026-10-05"))
        val newFile = File(tmp, "history/2026/10/2026-10-05_rt-11112222_abcd1234.md")

        assertTrue("new-date file written", newFile.exists())
        assertFalse("old-date file removed", oldFile.exists())
        assertEquals(
            "exactly one session on disk",
            1,
            repo.getSessionsInRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-06")).size,
        )
        assertEquals(1, repo.getAllCompletedSessions().size)
    }

    @Test
    fun `re-save with same date does not duplicate`() = runBlocking {
        repo.save(session("abcd1234", "2026-10-01"))
        repo.save(session("abcd1234", "2026-10-01"))

        assertEquals(
            1,
            repo.getSessionsInRange(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")).size,
        )
    }

    @Test
    fun `blank id is minted as 8 hex chars`() = runBlocking {
        val saved = repo.save(session("", "2026-10-01"))
        assertEquals(8, saved.id.length)
        assertTrue(saved.id.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
