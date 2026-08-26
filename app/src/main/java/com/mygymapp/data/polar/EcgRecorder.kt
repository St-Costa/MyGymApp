package com.mygymapp.data.polar

import android.util.Log
import com.mygymapp.data.repository.FileManager
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes raw ECG samples to disk during an active session.
 *
 * File format: `gymdata/ecg/{sessionId}.ecg`
 *   - Header (20 bytes):
 *     - Magic "MYGMECG1" (8 bytes ASCII)
 *     - Sample rate Int (4 bytes, big-endian) -- typically 130
 *     - Start timestamp Long (8 bytes, big-endian, ns since epoch)
 *   - Body: sequence of Int16 voltage samples (big-endian, µV clamped to ±32767)
 *
 * 130 Hz × 60 min × 2 B ≈ 940 KB per session uncompressed. File is ephemeral: local
 * analysis no longer runs at all (moved server-side, see docs/SYNC.md "Fourth record
 * type: raw ECG"). On register/abandon it's either deleted immediately (sync not
 * configured/enabled — nothing would ever consume it) or queued for upload and deleted
 * only once EcgSyncWorker confirms the server received it (or after 30 days pending).
 */
@Singleton
class EcgRecorder @Inject constructor(
    private val fileManager: FileManager,
) {
    companion object {
        private const val TAG = "EcgRecorder"
        private const val MAGIC = "MYGMECG1"
        private const val FLUSH_EVERY_SAMPLES = 260 // ~2 seconds at 130Hz
    }

    private var output: DataOutputStream? = null
    private var currentSessionId: String? = null
    private var sampleCount = 0
    private var samplesSinceFlush = 0

    @Synchronized
    fun start(sessionId: String, sampleRate: Int, startTimestampNs: Long) {
        stop() // ensure any previous is closed
        try {
            val dir = fileManager.getDir("ecg")
            val file = File(dir, "$sessionId.ecg")
            val stream = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))
            stream.writeBytes(MAGIC)
            stream.writeInt(sampleRate)
            stream.writeLong(startTimestampNs)
            output = stream
            currentSessionId = sessionId
            sampleCount = 0
            samplesSinceFlush = 0
            Log.d(TAG, "ECG recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ECG recording", e)
            output = null
            currentSessionId = null
        }
    }

    @Synchronized
    fun writeSample(voltageMicrovolts: Int) {
        val stream = output ?: return
        try {
            val clamped = voltageMicrovolts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            stream.writeShort(clamped)
            sampleCount++
            samplesSinceFlush++
            if (samplesSinceFlush >= FLUSH_EVERY_SAMPLES) {
                stream.flush()
                samplesSinceFlush = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ECG sample", e)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        output?.let {
            try {
                it.flush()
                it.close()
                Log.d(TAG, "ECG recording stopped: $sampleCount samples for session $currentSessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close ECG file", e)
            }
        }
        output = null
        currentSessionId = null
        sampleCount = 0
        samplesSinceFlush = 0
    }

    fun isRecording(): Boolean = output != null

    fun fileFor(sessionId: String): File {
        return File(fileManager.getDir("ecg"), "$sessionId.ecg")
    }

    fun delete(sessionId: String) {
        val file = fileFor(sessionId)
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted ECG file for session $sessionId")
        }
    }
}
