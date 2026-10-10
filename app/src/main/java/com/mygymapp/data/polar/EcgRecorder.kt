package com.mygymapp.data.polar

import android.util.Log
import com.mygymapp.data.repository.FileManager
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
/**
 * Minimal logging seam for [EcgRecorder]: `android.util.Log` throws in JVM unit tests, so
 * the recorder talks to this instead. Production binding lives in `di/AppModule`.
 */
interface EcgRecordLogger {
    fun d(message: String)
    fun e(message: String, error: Throwable? = null)
}

class AndroidEcgRecordLogger @Inject constructor() : EcgRecordLogger {
    override fun d(message: String) {
        Log.d(TAG, message)
    }

    override fun e(message: String, error: Throwable?) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
    }

    companion object {
        private const val TAG = "EcgRecorder"
    }
}

@Singleton
class EcgRecorder @Inject constructor(
    private val fileManager: FileManager,
    private val logger: EcgRecordLogger,
) {
    companion object {
        private const val TAG = "EcgRecorder"
        private const val MAGIC = "MYGMECG1"
        private const val FLUSH_EVERY_SAMPLES = 260 // ~2 seconds at 130Hz

        /**
         * Wall-clock ns-since-epoch for the file header. Must be derived from
         * `currentTimeMillis`, NEVER `System.nanoTime()`: nanoTime is monotonic-since-boot
         * (no epoch), and the server correlates cardio blocks to ECG samples via
         * `startTimestamp + sampleRate` — a boot-relative base shifted every offset by an
         * arbitrary amount. Pure seam (`nowMs` injectable) for unit tests.
         */
        fun epochTimestampNs(nowMs: Long = System.currentTimeMillis()): Long = nowMs * 1_000_000L
    }

    private var sampleChannel: Channel<Int>? = null
    private var currentSessionId: String? = null
    @Volatile private var sampleCount = 0

    // All file I/O runs here, never on the caller's thread: start/writeSample/stop are
    // invoked on the Polar SDK's BLE callback thread, and blocking it (even a ~2s-spaced
    // flush on a busy flash) stalls HR delivery on the same scheduler. Samples travel
    // through an UNLIMITED channel — trySend never blocks, drops nothing, and stop()
    // closes the channel so the writer drains every queued sample before closing.
    //
    // Single-threaded on purpose, and the file OPEN lives inside the writer task too: a
    // fast stop() → start() must not let the new recording's truncate+header land before
    // the retired writer's final flush (that interleaving corrupts the new file's tail).
    // FIFO order makes open/write/close of successive recordings strictly sequential with
    // zero blocking on the caller side. App-lifetime singleton, so neither the scope nor
    // the thread is ever shut down.
    private val writerDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ecg-writer") }
            .asCoroutineDispatcher()
    private val ioScope = CoroutineScope(SupervisorJob() + writerDispatcher)

    @Synchronized
    fun start(sessionId: String, sampleRate: Int, startTimestampNs: Long) {
        stop() // ensure any previous is closed
        val channel = Channel<Int>(Channel.UNLIMITED)
        sampleChannel = channel
        currentSessionId = sessionId
        sampleCount = 0
        logger.d("ECG recording started: $sessionId")
        ioScope.launch {
            val stream = try {
                val dir = fileManager.getDir("ecg")
                val file = File(dir, "$sessionId.ecg")
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).also { s ->
                    s.writeBytes(MAGIC)
                    s.writeInt(sampleRate)
                    s.writeLong(startTimestampNs)
                    s.flush()
                }
            } catch (e: Exception) {
                logger.e("Failed to start ECG recording", e)
                return@launch
            }
            var sinceFlush = 0
            try {
                for (v in channel) {
                    stream.writeShort(
                        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
                    )
                    sampleCount++
                    if (++sinceFlush >= FLUSH_EVERY_SAMPLES) {
                        stream.flush()
                        sinceFlush = 0
                    }
                }
            } catch (e: Exception) {
                logger.e("Failed to write ECG sample", e)
            }
            try {
                stream.flush()
                stream.close()
                logger.d("ECG recording stopped: $sampleCount samples for session $sessionId")
            } catch (e: Exception) {
                logger.e("Failed to close ECG file", e)
            }
        }
    }

    @Synchronized
    fun writeSample(voltageMicrovolts: Int) {
        // Non-blocking by construction: UNLIMITED trySend always succeeds while recording.
        sampleChannel?.trySend(voltageMicrovolts)
    }

    @Synchronized
    fun stop() {
        val channel = sampleChannel
        val session = currentSessionId
        sampleChannel = null
        currentSessionId = null
        // Closing lets the writer coroutine drain the queue, then flush + close the file.
        // A fast stop→start only retires this recording; the new start() opens its own file
        // (after the retired writer, by FIFO order — see writerDispatcher).
        channel?.close()
        if (channel != null) {
            logger.d("ECG recording stopping for session $session (draining)")
        }
    }

    /**
     * Suspends until every recording task submitted so far (open/write/close) has run.
     * Call before reading a just-stopped file (e.g. the end-of-session sync enqueue),
     * otherwise the read can miss the final not-yet-flushed tail. FIFO order makes this
     * a one-empty-task round-trip.
     */
    suspend fun awaitIdle() {
        ioScope.launch { }.join()
    }

    @Synchronized
    fun isRecording(): Boolean = sampleChannel != null

    fun fileFor(sessionId: String): File {
        return File(fileManager.getDir("ecg"), "$sessionId.ecg")
    }

    fun delete(sessionId: String) {
        val file = fileFor(sessionId)
        if (file.exists()) {
            file.delete()
            logger.d("Deleted ECG file for session $sessionId")
        }
    }
}
