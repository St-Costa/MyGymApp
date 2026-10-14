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
 * Persistent event log for post-mortem debugging (docs/CONVENTIONS.md#crash-reports--action-trail).
 *
 * Writes to `filesDir/gymdata/logs/app.log` in addition to Android logcat. Kept for
 * [RETENTION_DAYS] (same horizon as crash reports) — far longer than Android's own records.
 * Only key events are logged (not per-sample data). Pruning ([LogRetention]) runs at each
 * app start and whenever the file grows past [MAX_BYTES]: entries older than
 * [RETENTION_DAYS] go first, then the oldest until the file fits.
 *
 * Levels:
 *  - [d] — logcat + in-memory [Breadcrumbs] only, never the file (chatty diagnostics).
 *  - [i] / [w] / [e] — logcat + file + [Breadcrumbs]. [e] with a throwable also writes the
 *    full stack trace as indented continuation lines.
 *
 * Format: `2026-06-06 09:31:22 I PolarManager: message text`
 *
 * Read on the phone: Options → Debug → LOGS & CRASHES. From a PC:
 * `adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        private const val LOG_TAG = "AppLogger"
        const val RETENTION_DAYS = 90L
        // ~5–20 KB/day of key events → 90 days fits well under this; the cap only bites if
        // something floods the log (e.g. a BLE reconnect loop).
        const val MAX_BYTES = 5L * 1024 * 1024
        /** Size check cadence: a stat() every N writes, not on every line. */
        private const val SIZE_CHECK_EVERY = 100
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun logsDir(context: Context): File = File(context.filesDir, "gymdata/logs")
        fun logFile(context: Context): File = File(logsDir(context), "app.log")

        /** One entry: the header line plus, for a throwable, its indented stack trace. */
        fun formatEntry(at: LocalDateTime, level: Char, tag: String, message: String, throwable: Throwable? = null): String {
            val head = "${TS_FMT.format(at)} $level $tag: ${message.replace('\n', ' ')}\n"
            if (throwable == null) return head
            val trace = CrashReportFormat.stackTrace(throwable).lineSequence()
                .joinToString("\n", postfix = "\n") { "    $it" }
            return head + trace
        }

        /**
         * Appends without a Hilt instance — for [CrashReporter]'s uncaught-exception handler,
         * which can run before (or without) the DI graph.
         */
        fun appendRaw(context: Context, text: String) {
            try {
                val f = logFile(context)
                f.parentFile?.mkdirs()
                f.appendText(text)
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Failed to write log entry", t)
            }
        }
    }

    private val file: File = run {
        val dir = logsDir(context)
        dir.mkdirs()
        logFile(context)
    }

    private var writesSinceSizeCheck = 0

    init {
        prune()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        Breadcrumbs.add(tag, message)
    }

    fun i(tag: String, message: String) {
        write('I', tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        write('W', tag, message, throwable)
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write('E', tag, message, throwable)
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    @Synchronized
    private fun write(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        Breadcrumbs.add(tag, if (throwable != null) "$level $message — $throwable" else "$level $message")
        try {
            file.appendText(formatEntry(LocalDateTime.now(), level, tag, message, throwable))
            if (++writesSinceSizeCheck >= SIZE_CHECK_EVERY) {
                writesSinceSizeCheck = 0
                if (file.length() > MAX_BYTES) prune()
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Failed to write log entry", t)
        }
    }

    @Synchronized
    private fun prune() {
        if (!file.exists()) return
        try {
            val cutoff = LocalDate.now().minusDays(RETENTION_DAYS).toString() // "yyyy-MM-dd"
            val lines = file.readLines()
            // Prune to half the cap so a busy day doesn't re-trigger it every 100 writes.
            val kept = LogRetention.prune(lines, cutoff, MAX_BYTES / 2)
            if (kept.size < lines.size) {
                AtomicFiles.writeTextAtomically(
                    file,
                    kept.joinToString("\n", postfix = if (kept.isNotEmpty()) "\n" else ""),
                )
                Log.d(LOG_TAG, "Pruned ${lines.size - kept.size} log lines (cutoff $cutoff)")
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Failed to prune log file", t)
        }
    }
}
