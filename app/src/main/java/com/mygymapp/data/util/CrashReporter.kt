package com.mygymapp.data.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.mygymapp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Crash and abnormal-exit reports in `gymdata/logs/crashes/` (docs/CONVENTIONS.md#crash-reports--action-trail).
 *
 * Two sources, because no single one sees everything:
 *  1. [install] — a default uncaught-exception handler. Covers every Java/Kotlin crash on any
 *     thread (coroutines included: an uncaught coroutine exception ends up here too). Writes
 *     `crash-*.txt` with the stack trace, the current screen and the [Breadcrumbs] trail
 *     *synchronously*, then hands over to the platform handler so the app still dies normally.
 *  2. [recordPreviousExits] — at boot, Android's `ApplicationExitInfo` history (API 30+) for
 *     the deaths an in-process handler can't catch: ANR (with the thread dump), native crash,
 *     out-of-memory, system kill. Every new exit is one `app.log` line; the serious ones also
 *     get a report file. The breadcrumb trail of that dead process is gone by then — only
 *     `app.log` and the exit info survive.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "CrashReporter"
        /** ANR thread dumps can be several MB; the first part (main thread first) is what matters. */
        private const val MAX_TRACE_BYTES = 512 * 1024
        private const val TRAIL_SECTION = "last actions (oldest first)"

        fun crashesDir(context: Context): File = File(AppLogger.logsDir(context), "crashes")
        private fun seenFile(context: Context) = File(AppLogger.logsDir(context), ".exit_info_seen")

        /**
         * Installs the uncaught-exception handler. Call first thing in `Application.onCreate`,
         * before `super.onCreate()` — it needs no DI, so a crash while Hilt builds the graph is
         * caught too. Idempotent.
         */
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is Handler) return
            Thread.setDefaultUncaughtExceptionHandler(Handler(context, previous))
        }

        fun appLine(): String =
            "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"

        fun deviceLine(): String =
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        /** Writes [text] as a new report; `-2`, `-3`… suffix on a same-second name clash. */
        fun writeReport(context: Context, kind: String, at: LocalDateTime, text: String): File {
            val dir = crashesDir(context).apply { mkdirs() }
            val base = CrashReportFormat.fileName(kind, at).removeSuffix(".txt")
            var f = File(dir, "$base.txt")
            var n = 2
            while (f.exists()) f = File(dir, "$base-${n++}.txt")
            // Plain write, not AtomicFiles: inside a dying process the simplest path is the one
            // most likely to complete, and a truncated report still beats none.
            f.writeText(text)
            return f
        }
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val at = LocalDateTime.now()
                val text = CrashReportFormat.format(
                    title = "MyGymApp crash report",
                    at = at,
                    fields = listOf(
                        "kind" to "crash (uncaught exception)",
                        "app" to appLine(),
                        "device" to deviceLine(),
                        "thread" to thread.name,
                        "screen" to Breadcrumbs.currentScreen,
                    ),
                    sections = listOf(
                        "exception" to CrashReportFormat.stackTrace(error),
                        TRAIL_SECTION to Breadcrumbs.snapshot().formatTrail(),
                    ),
                )
                val f = writeReport(context, "crash", at, text)
                AppLogger.appendRaw(
                    context,
                    AppLogger.formatEntry(at, 'E', TAG, "Uncaught exception on '${thread.name}' → ${f.name}", error),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to write crash report", t)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Report files, newest first. */
    fun listReports(): List<File> =
        (crashesDir(context).listFiles()?.toList() ?: emptyList())
            .filter { CrashReportFormat.timestampOf(it.name) != null }
            .sortedWith(compareByDescending<File> { CrashReportFormat.timestampOf(it.name) }.thenByDescending { it.name })

    /** Applies [CrashReportFormat.expired] to the report directory. */
    suspend fun pruneReports() = withContext(Dispatchers.IO) {
        val dir = crashesDir(context)
        val names = dir.list()?.toList() ?: return@withContext
        for (name in CrashReportFormat.expired(names, LocalDate.now())) {
            File(dir, name).delete()
        }
    }

    /** Boot pass over Android's exit history; no-op below API 30. Never throws. */
    suspend fun recordPreviousExits() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext
        try {
            recordPreviousExitsApi30()
        } catch (t: Throwable) {
            appLogger.w(TAG, "Reading previous process exits failed", t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordPreviousExitsApi30() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val seen = seenFile(context)
        val lastSeen = runCatching { seen.readText().trim().toLong() }.getOrDefault(0L)
        val fresh = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        for (info in fresh) record(info)
        AtomicFiles.writeTextAtomically(seen, fresh.last().timestamp.toString())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun record(info: ApplicationExitInfo) {
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(info.timestamp), ZoneId.systemDefault())
        val reason = ExitReasons.name(info.reason)
        val summary = "Process exit at ${at.withNano(0)}: $reason" +
            (info.description?.let { " — $it" } ?: "") +
            " (pid ${info.pid}, importance ${info.importance}, rss ${info.rss / 1024}MB)"
        val kind = ExitReasons.reportKind(info.reason)
        if (kind == null) {
            appLogger.i(TAG, summary)
            return
        }
        val trace = if (ExitReasons.hasTextTrace(info.reason)) {
            runCatching { info.traceInputStream?.use { readCapped(it) } }.getOrNull()
        } else null
        val text = CrashReportFormat.format(
            title = "MyGymApp abnormal exit report",
            at = at,
            fields = listOf(
                "kind" to "$kind ($reason, recorded at next launch)",
                "app" to appLine(),
                "device" to deviceLine(),
                "description" to info.description,
                "status" to info.status.toString(),
                "importance" to info.importance.toString(),
                "pss / rss" to "${info.pss / 1024}MB / ${info.rss / 1024}MB",
            ),
            sections = buildList {
                if (trace != null) add("trace" to trace)
                add(
                    "note" to "The action trail of the dead process is not available for this kind of " +
                        "exit — check app.log around the time above.",
                )
            },
        )
        val f = writeReport(context, kind, at, text)
        appLogger.w(TAG, "$summary → ${f.name}")
    }

    private fun readCapped(input: InputStream): String {
        val buf = ByteArray(MAX_TRACE_BYTES)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        val truncated = n == buf.size && input.read() >= 0
        return String(buf, 0, n, Charsets.UTF_8) + if (truncated) "\n… (truncated at ${MAX_TRACE_BYTES / 1024} KB)" else ""
    }
}
