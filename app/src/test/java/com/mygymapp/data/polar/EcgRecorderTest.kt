package com.mygymapp.data.polar

import com.mygymapp.data.repository.FileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files

/**
 * Sprint 5: samples travel through an IO queue (the BLE thread never blocks on file I/O)
 * and `stop()` drains every queued sample before closing. The drain is async, so tests
 * poll for the expected size with a deadline instead of sleeping fixed amounts.
 */
class EcgRecorderTest {

    private lateinit var tmp: File
    private lateinit var recorder: EcgRecorder

    private object NoOpLogger : EcgRecordLogger {
        override fun d(message: String) = Unit
        override fun e(message: String, error: Throwable?) = Unit
    }

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("ecg-rec-test").toFile()
        recorder = EcgRecorder(FileManager(tmp), NoOpLogger)
    }

    private fun ecgFile(id: String) = File(tmp, "ecg/$id.ecg")

    @Test
    fun `start-write-stop persists header plus every sample`() = runBlocking {
        recorder.start("s1", 130, EcgRecorder.epochTimestampNs(1_000L))
        assertTrue(recorder.isRecording())
        repeat(300) { recorder.writeSample(1000 + it) }
        recorder.stop()
        assertFalse(recorder.isRecording())

        // Deterministic: awaitIdle joins the FIFO writer past the close.
        recorder.awaitIdle()
        val file = ecgFile("s1")
        assertEquals(20 + 2 * 300, file.length())

        DataInputStream(file.inputStream()).use { input ->
            val magic = ByteArray(8)
            input.readFully(magic)
            assertEquals("MYGMECG1", String(magic, Charsets.US_ASCII))
            assertEquals(130, input.readInt())
            assertEquals(1_000L * 1_000_000L, input.readLong())
            assertEquals(1000.toShort(), input.readShort())
        }
    }

    @Test
    fun `writeSample and stop without start are no-ops`() {
        recorder.writeSample(5)
        recorder.stop()
        assertFalse(recorder.isRecording())
    }

    @Test
    fun `restart replaces the file and drains the new samples`() = runBlocking {
        recorder.start("s2", 130, EcgRecorder.epochTimestampNs(2_000L))
        repeat(10) { recorder.writeSample(it) }
        recorder.stop()

        recorder.start("s2", 130, EcgRecorder.epochTimestampNs(3_000L))
        repeat(5) { recorder.writeSample(100 + it) }
        recorder.stop()
        recorder.awaitIdle()

        val file = ecgFile("s2")
        assertEquals(20 + 2 * 5, file.length())
        DataInputStream(file.inputStream()).use { input ->
            input.skipBytes(8 + 4)
            assertEquals(3_000L * 1_000_000L, input.readLong())
            assertEquals(100.toShort(), input.readShort())
        }
    }
}
