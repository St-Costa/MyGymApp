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

/**
 * Coverage for [BatteryLifeRepository.addActiveSeconds] — the connected-span accounting
 * behind the H10 active-hours counter. Spans arrive from PolarManager on disconnect and on
 * every in-connection heartbeat; battery callbacks only seed/detect swaps (see
 * [BatteryLifeReducerTest]), so this is what actually moves the counter during a session.
 */
class BatteryLifeSpanTest {

    private lateinit var tmp: File
    private lateinit var repo: BatteryLifeRepository

    private val day = LocalDate.of(2026, 6, 10)

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("battery-life-span-test").toFile()
        repo = BatteryLifeRepository(FileManager(tmp))
    }

    @Test
    fun `spans accumulate across heartbeats and disconnect`() = runBlocking {
        repo.onBatteryLevel(level = 90, nowEpochSec = 1_000, today = day)
        repo.addActiveSeconds(300) // heartbeat
        repo.addActiveSeconds(300) // heartbeat
        val flushed = repo.addActiveSeconds(5_400) // disconnect tail: 1.5 h session
        assertNotNull(flushed)
        assertEquals(6_000L, flushed!!.activeSeconds)
        assertEquals(6_000L, repo.peek()!!.activeSeconds)
    }

    @Test
    fun `non-positive spans are ignored`() = runBlocking {
        repo.onBatteryLevel(level = 90, nowEpochSec = 1_000, today = day)
        repo.addActiveSeconds(600)
        repo.addActiveSeconds(0)
        repo.addActiveSeconds(-30) // clock skew
        assertEquals(600L, repo.peek()!!.activeSeconds)
    }

    @Test
    fun `span with no seeded cell is dropped, not invented`() = runBlocking {
        assertNull(repo.addActiveSeconds(3_600))
        assertNull(repo.peek())
    }

    @Test
    fun `accumulated spans feed the swap history`() = runBlocking {
        repo.onBatteryLevel(level = 60, nowEpochSec = 0, today = day)
        // 3 h of connected spans; a 100% reading alone no longer counts as a swap.
        repo.addActiveSeconds(3 * 3_600)
        val afterReading = repo.onBatteryLevel(level = 100, nowEpochSec = 3 * 3_600 + 60, today = day)
        assertEquals(3 * 3_600L, afterReading.activeSeconds)
        assertTrue(afterReading.pastLifeSeconds.isEmpty())
        // The user confirms the swap manually.
        val afterSwap = repo.resetForNewBattery(currentLevel = 100, nowEpochSec = 3 * 3_600 + 120, today = day)!!
        assertEquals(listOf(3 * 3_600L), afterSwap.pastLifeSeconds)
        assertEquals(3.0, afterSwap.avgLifeHours!!, 1e-9)
        assertEquals(0L, afterSwap.activeSeconds)
    }

    @Test
    fun `manual reset restarts the counter and folds credible history`() = runBlocking {
        repo.onBatteryLevel(level = 70, nowEpochSec = 0, today = day)
        repo.addActiveSeconds(10 * 3_600)
        // Fresh cell also reports ~100%: the auto jump-detector may still see it, but the
        // manual path works regardless of the reported levels.
        val reset = repo.resetForNewBattery(currentLevel = 100, nowEpochSec = 10 * 3_600, today = day)
        assertNotNull(reset)
        assertEquals(0L, reset!!.activeSeconds)
        assertEquals(day, reset.installedAtDate)
        assertEquals(100, reset.installedAtLevel)
        assertEquals(listOf(10 * 3_600L), reset.pastLifeSeconds)
        assertEquals(10.0, reset.avgLifeHours!!, 1e-9)
    }

    @Test
    fun `manual reset drops a near-zero outgoing cell from the average`() = runBlocking {
        repo.onBatteryLevel(level = 100, nowEpochSec = 0, today = day)
        repo.addActiveSeconds(600) // 10 min — pulled & reinserted
        val reset = repo.resetForNewBattery(currentLevel = 100, nowEpochSec = 600, today = day)
        assertNotNull(reset)
        assertEquals(0L, reset!!.activeSeconds)
        assertTrue(reset.pastLifeSeconds.isEmpty())
        assertNull(reset.avgLifeHours)
    }

    @Test
    fun `manual reset with no prior state seeds from the live level`() = runBlocking {
        val seeded = repo.resetForNewBattery(currentLevel = 95, nowEpochSec = 0, today = day)
        assertNotNull(seeded)
        assertEquals(day, seeded!!.installedAtDate)
        assertEquals(0L, seeded.activeSeconds)
        // ...but with no level at all there is nothing to seed from.
        val fresh = BatteryLifeRepository(FileManager(
            Files.createTempDirectory("battery-life-span-empty-test").toFile(),
        ))
        assertNull(fresh.resetForNewBattery(currentLevel = null))
    }
}
