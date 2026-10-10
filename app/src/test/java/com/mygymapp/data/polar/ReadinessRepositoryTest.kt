package com.mygymapp.data.polar

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Round-trip coverage for [ReadinessRepository]'s Markdown+YAML persistence, focused on the
 * `sleepQuality` field added in Phase (sleep-quality box): it must survive serialize→parse,
 * read back as `null` when absent from an older file, and be patchable in place via
 * [ReadinessRepository.updateSleepQuality] without disturbing any other field.
 */
class ReadinessRepositoryTest {

    private lateinit var tmp: File
    private lateinit var repo: ReadinessRepository

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("readiness-repo-test").toFile()
        repo = ReadinessRepository(FileManager(tmp))
    }

    @Test
    fun `save then getById round-trips sleepQuality`() = runBlocking {
        val saved = repo.save(
            readiness = "NORMAL",
            lnRmssd = 4.12,
            restingHr = 54,
            vo2max = 48.2,
            recommendation = "HRV within normal range. Proceed with planned workout.",
            stepsPreviousDay = 12_641,
            sleepQuality = 4,
        )

        val loaded = repo.getById(saved.id)!!
        assertEquals(4, loaded.sleepQuality)
        assertEquals("NORMAL", loaded.readiness)
        assertEquals(54, loaded.restingHr)
        assertEquals(12_641L, loaded.stepsPreviousDay)
    }

    @Test
    fun `sleepQuality is null when not provided`() = runBlocking {
        val saved = repo.save(
            readiness = "GOOD",
            lnRmssd = 4.5,
            restingHr = 50,
            vo2max = 50.0,
            recommendation = "",
        )

        assertNull(repo.getById(saved.id)!!.sleepQuality)
    }

    @Test
    fun `updateSleepQuality patches only that field`() = runBlocking {
        val saved = repo.save(
            readiness = "LIGHT_DAY",
            lnRmssd = 3.7,
            restingHr = 58,
            vo2max = 46.0,
            recommendation = "HRV moderately low. Take it easy.",
            stepsAvgPerDay = 9_500.0,
            stepsDaysSpanned = 3,
            stepsPreviousDay = 8_800,
        )
        assertNull(saved.sleepQuality)

        val updated = repo.updateSleepQuality(saved.id, 2)!!
        assertEquals(2, updated.sleepQuality)

        val reloaded = repo.getById(saved.id)!!
        assertEquals(2, reloaded.sleepQuality)
        // Everything else untouched.
        assertEquals("LIGHT_DAY", reloaded.readiness)
        assertEquals(3.7, reloaded.lnRmssd, 1e-9)
        assertEquals(58, reloaded.restingHr)
        assertEquals(46.0, reloaded.vo2max, 1e-9)
        assertEquals("HRV moderately low. Take it easy.", reloaded.recommendation)
        assertEquals(9_500.0, reloaded.stepsAvgPerDay!!, 1e-9)
        assertEquals(3, reloaded.stepsDaysSpanned)
        assertEquals(8_800L, reloaded.stepsPreviousDay)
        assertEquals(saved.measuredAt, reloaded.measuredAt)
    }

    @Test
    fun `updateSleepQuality returns null for an unknown id`() = runBlocking {
        assertNull(repo.updateSleepQuality("deadbeef", 3))
    }

    @Test
    fun `save stamps an offset-bearing measuredAt`() = runBlocking {
        val saved = repo.save(
            readiness = "NORMAL",
            lnRmssd = 4.0,
            restingHr = 55,
            vo2max = 47.0,
            recommendation = "",
        )

        // Parses as OffsetDateTime — the bare LocalDateTime of older files would throw here.
        assertNotNull(runCatching { OffsetDateTime.parse(saved.measuredAt) }.getOrNull())
        // ...and the new readers resolve it to today.
        assertEquals(LocalDate.now(), measuredDateOf(saved.measuredAt))
        assertNotNull(repo.getLatestForDate(LocalDate.now()))
    }

    @Test
    fun `measuredDateOf reads legacy bare LocalDateTime too`() {
        assertEquals(LocalDate.of(2026, 8, 5), measuredDateOf("2026-08-05T07:04:10.123"))
        assertEquals(LocalDate.of(2026, 8, 5), measuredDateOf("2026-08-05T07:04:10.123+02:00"))
        assertNull(measuredDateOf("not-a-date"))
        assertNull(measuredDateOf(""))
    }

    @Test
    fun `measuredInstantOf orders mixed old and new formats chronologically`() {
        val legacy = measuredInstantOf("2026-08-05T07:04:10.123")
        val newer = measuredInstantOf("2026-08-06T07:04:10.123+02:00")

        assertTrue(legacy < newer)
    }
}
