package com.mygymapp.data.util

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure helpers for the crash/ANR report files under `gymdata/logs/crashes/`
 * (tested in `CrashReportFormatTest`). The Android side lives in [CrashReporter].
 */
object CrashReportFormat {

    /** Max report files kept, newest first; older ones are pruned at boot. */
    const val MAX_REPORTS = 50
    const val MAX_REPORT_AGE_DAYS = 90L

    private val NAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val HEADER_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val NAME = Regex("""^([a-z]+)-(\d{8}-\d{6})(?:-\d+)?\.txt$""")

    /** `crash-20261014-163012.txt`. Callers add `-2`, `-3`… on a same-second collision. */
    fun fileName(kind: String, at: LocalDateTime): String = "$kind-${NAME_TS.format(at)}.txt"

    /** Timestamp encoded in a report file name, or null if the name isn't ours. */
    fun timestampOf(name: String): LocalDateTime? {
        val m = NAME.matchEntire(name) ?: return null
        return runCatching { LocalDateTime.parse(m.groupValues[2], NAME_TS) }.getOrNull()
    }

    /** Kind prefix (`crash`, `anr`, …) of a report file name, or null if the name isn't ours. */
    fun kindOf(name: String): String? = NAME.matchEntire(name)?.groupValues?.get(1)

    /**
     * Report files to delete: anything older than [maxAgeDays] before [today], plus everything
     * past the newest [maxCount]. Names that don't parse as reports are never touched.
     */
    fun expired(
        names: List<String>,
        today: LocalDate,
        maxCount: Int = MAX_REPORTS,
        maxAgeDays: Long = MAX_REPORT_AGE_DAYS,
    ): List<String> {
        val cutoff = today.minusDays(maxAgeDays).atStartOfDay()
        val dated = names.mapNotNull { n -> timestampOf(n)?.let { n to it } }
            .sortedWith(compareByDescending<Pair<String, LocalDateTime>> { it.second }.thenByDescending { it.first })
        return dated.filterIndexed { i, (_, ts) -> i >= maxCount || ts.isBefore(cutoff) }.map { it.first }
    }

    /**
     * Report body: a title, `key: value` header lines (null values skipped), then each
     * section under a `--- name ---` rule. Plain text so it reads fine in any viewer or chat.
     */
    fun format(
        title: String,
        at: LocalDateTime,
        fields: List<Pair<String, String?>>,
        sections: List<Pair<String, String>>,
    ): String = buildString {
        appendLine(title)
        appendLine("time: ${HEADER_TS.format(at)}")
        for ((k, v) in fields) if (v != null) appendLine("$k: $v")
        for ((name, body) in sections) {
            appendLine()
            appendLine("--- $name ---")
            appendLine(body.ifBlank { "(empty)" })
        }
    }

    /** Full stack trace including causes and suppressed exceptions. */
    fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString().trimEnd()
    }
}

/**
 * Classification of Android's `ApplicationExitInfo.reason` codes (API 30+), kept as plain
 * ints so it stays pure/testable. Read at boot by [CrashReporter.recordPreviousExits]: every
 * new exit becomes one `app.log` line; the ones in [reportKind] also get a report file,
 * because they are the deaths our in-process handler cannot see (ANR, native crash, OOM).
 */
object ExitReasons {
    private val NAMES = mapOf(
        0 to "UNKNOWN", 1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH",
        5 to "CRASH_NATIVE", 6 to "ANR", 7 to "INITIALIZATION_FAILURE", 8 to "PERMISSION_CHANGE",
        9 to "EXCESSIVE_RESOURCE_USAGE", 10 to "USER_REQUESTED", 11 to "USER_STOPPED",
        12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER", 15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED",
    )

    fun name(reason: Int): String = NAMES[reason] ?: "REASON_$reason"

    /**
     * Report-file prefix for exits worth a full report, null for routine ones (user swiped
     * the app away, background kill, update…). CRASH (4) is null too: the Java crash was
     * already written by the uncaught-exception handler, the boot pass only logs a line.
     */
    fun reportKind(reason: Int): String? = when (reason) {
        6 -> "anr"
        5 -> "native"
        3, 9 -> "oom"
        2, 7 -> "killed"
        else -> null
    }

    /** Whether the exit's trace stream is human-readable text (ANR thread dump). The native
     * tombstone is a protobuf on API 31+, so it is not copied into the report. */
    fun hasTextTrace(reason: Int): Boolean = reason == 6
}
