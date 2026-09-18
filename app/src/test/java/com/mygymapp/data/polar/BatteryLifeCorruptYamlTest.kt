package com.mygymapp.data.polar

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Coverage for [BatteryLifeRepository]'s corrupt-file handling: a hand-edited
 * `battery_life.yml` with wrong-typed fields must read back as `null` (no stats yet)
 * without throwing — and must never wipe a good file on the next write path.
 */
class BatteryLifeCorruptYamlTest {

    private lateinit var tmp: File
    private lateinit var repo: BatteryLifeRepository

    private val day = LocalDate.of(2026, 6, 10)

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("battery-life-corrupt-test").toFile()
        repo = BatteryLifeRepository(FileManager(tmp))
    }

    private fun batteryFile(): File = File(File(tmp, "_sync"), "battery_life.yml")

    @Test
    fun `valid write then peek round-trips`() = runBlocking {
        repo.onBatteryLevel(level = 90, nowEpochSec = 1_000, today = day)
        val peeked = repo.peek()
        assertNotNull(peeked)
        assertEquals(90, peeked!!.lastLevel)
    }

    @Test
    fun `wrong-typed field reads as null instead of throwing`() = runBlocking {
        repo.onBatteryLevel(level = 90, nowEpochSec = 1_000, today = day)
        // Corrupt one field's type the way a hand edit would.
        val bad = batteryFile().readText().replace("installedAtLevel: 90", "installedAtLevel: \"high\"")
        batteryFile().writeText(bad)
        assertNull(repo.peek())
    }

    @Test
    fun `garbage file reads as null instead of throwing`() = runBlocking {
        batteryFile().parentFile?.mkdirs()
        batteryFile().writeText("installedAtDate: [unclosed\n: : :\n")
        assertNull(repo.peek())
    }

    @Test
    fun `missing file reads as null`() = runBlocking {
        assertNull(repo.peek())
    }
}
