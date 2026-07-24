package com.mygymapp.data.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent event log for post-mortem debugging.
 *
 * Writes to `filesDir/gymdata/logs/app.log` in addition to Android logcat.
 * Only key events are logged (not per-sample data). At each app start the
 * file is pruned: lines older than [RETENTION_DAYS] are dropped.
 *
 * Format: `2026-06-06 09:31:22 I PolarManager: message text`
 *
 * Read from the device: `adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`
 *
 * Delivery is asynchronous: the public i/w/e methods do not touch disk on the
 * caller's thread. Log lines are pushed to an unlimited Channel and drained
 * by a single consumer coroutine on Dispatchers.IO, batching whatever arrived
 * while the previous write was in flight so file.appendText opens the stream
 * once per burst rather than once per call. This matters on the Polar Rx
 * thread which fires several i/w/e per second during a session.
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val LOG_TAG = "AppLogger"
        private const val RETENTION_DAYS = 10L
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        // Prefix length of "yyyy-MM-dd" used for pruning
        private const val DATE_PREFIX_LEN = 10
    }

    private val file: File = run {
        val dir = File(context.filesDir, "gymdata/logs")
        dir.mkdirs()
        File(dir, "app.log")
    }

    // Dedicated scope: singleton, lives for the process. SupervisorJob so a
    // consumer exception can't tear the whole scope down.
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(Channel.UNLIMITED)

    init {
        // Both prune and consume run off the caller's thread — previous
        // behaviour blocked Hilt injection on Main during startup.
        logScope.launch { pruneOldEntries() }
        logScope.launch { consumeLoop() }
    }

    fun i(tag: String, message: String) = write('I', tag, message).also { Log.i(tag, message) }
    fun w(tag: String, message: String) = write('W', tag, message).also { Log.w(tag, message) }
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write('E', tag, if (throwable != null) "$message — ${throwable.message}" else message)
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    private fun write(level: Char, tag: String, message: String) {
        val line = "${LocalDateTime.now().format(TS_FMT)} $level $tag: $message\n"
        // trySend on an UNLIMITED channel only fails if it was closed, which we
        // never do in this app — logcat still gets the line either way.
        logChannel.trySend(line)
    }

    private suspend fun consumeLoop() {
        for (first in logChannel) {
            val batch = StringBuilder(first)
            // Drain anything already queued so one appendText covers the burst.
            while (true) {
                val next = logChannel.tryReceive().getOrNull() ?: break
                batch.append(next)
            }
            try {
                file.appendText(batch.toString())
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Failed to write log entry", t)
            }
        }
    }

    private fun pruneOldEntries() {
        if (!file.exists()) return
        try {
            val cutoff = LocalDate.now().minusDays(RETENTION_DAYS).toString() // "yyyy-MM-dd"
            val lines = file.readLines()
            val kept = lines.filter { line ->
                if (line.length < DATE_PREFIX_LEN) return@filter true // keep unparseable lines
                line.substring(0, DATE_PREFIX_LEN) >= cutoff
            }
            if (kept.size < lines.size) {
                file.writeText(kept.joinToString("\n", postfix = if (kept.isNotEmpty()) "\n" else ""))
                Log.d(LOG_TAG, "Pruned ${lines.size - kept.size} log entries older than $cutoff")
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Failed to prune log file", t)
        }
    }
}
