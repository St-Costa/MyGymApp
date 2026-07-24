package com.mygymapp.data.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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

    init {
        pruneOldEntries()
    }

    fun i(tag: String, message: String) = write('I', tag, message).also { Log.i(tag, message) }
    fun w(tag: String, message: String) = write('W', tag, message).also { Log.w(tag, message) }
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write('E', tag, if (throwable != null) "$message — ${throwable.message}" else message)
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    @Synchronized
    private fun write(level: Char, tag: String, message: String) {
        try {
            val line = "${LocalDateTime.now().format(TS_FMT)} $level $tag: $message\n"
            file.appendText(line)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Failed to write log entry", t)
        }
    }

    @Synchronized
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
