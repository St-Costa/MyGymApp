package com.mygymapp.data.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One entry of the in-memory action trail (see [Breadcrumbs]). */
data class Breadcrumb(
    val atMillis: Long,
    val category: String,
    val message: String,
    /** Coalescing key: a new crumb with the same key as the newest one replaces it. */
    val key: String? = null,
)

/**
 * Fixed-size ring buffer of the latest [Breadcrumb]s. Pure (no Android), thread-safe.
 *
 * Coalescing keeps a picker scroll from flooding the trail: `updateWeight` fires on every
 * tick, but recorded with key `strength.weight.2` the run collapses into one crumb holding
 * the latest value. A different key in between breaks the run, so "weight → reps → weight"
 * stays three crumbs.
 */
class BreadcrumbBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val items = ArrayDeque<Breadcrumb>(capacity)

    @Synchronized
    fun record(category: String, message: String, key: String? = null) {
        val crumb = Breadcrumb(clock(), category, message, key)
        if (key != null && items.lastOrNull()?.key == key) {
            items.removeLast()
        } else if (items.size >= capacity) {
            items.removeFirst()
        }
        items.addLast(crumb)
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<Breadcrumb> = items.toList()

    @Synchronized
    fun clear() = items.clear()

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}

/**
 * Process-wide action trail attached to crash reports (docs/CONVENTIONS.md#crash-reports--action-trail).
 *
 * A global object (not a Hilt singleton) on purpose: the uncaught-exception handler must be
 * able to read it with no DI graph, and recording from a ViewModel must not need a new
 * constructor parameter. Memory only — nothing here touches disk; [CrashReporter] dumps
 * [snapshot] into the report file when the process dies.
 *
 * Record *key actions* only (navigation, set edits, complete/switch/exit, BLE/sync state):
 * never per-sample data. Use a `key` for anything that fires repeatedly.
 */
object Breadcrumbs {
    private val buffer = BreadcrumbBuffer()

    /** Last route reported by the NavController listener, e.g. `workout/ab12/strength/ex-…`. */
    @Volatile
    var currentScreen: String? = null
        private set

    fun add(category: String, message: String, key: String? = null) =
        buffer.record(category, message, key)

    fun onScreen(route: String) {
        currentScreen = route
        buffer.record("nav", route)
    }

    fun snapshot(): List<Breadcrumb> = buffer.snapshot()
}

private val CRUMB_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** `09:31:22.418 [strength] set 3 weight → 62.5`, one per line, oldest first. */
fun List<Breadcrumb>.formatTrail(zone: ZoneId = ZoneId.systemDefault()): String =
    joinToString("\n") { c ->
        "${CRUMB_TS.format(Instant.ofEpochMilli(c.atMillis).atZone(zone))} [${c.category}] ${c.message}"
    }

/**
 * Substitutes `{arg}` placeholders of a nav route pattern with the destination's actual
 * arguments: `workout/{sessionId}/strength/{exerciseId}` → `workout/ab12/strength/ex-9f…`.
 * Unknown placeholders stay as-is; query params with blank values are kept (cheap to read).
 */
fun describeRoute(pattern: String?, args: Map<String, Any?>): String {
    if (pattern == null) return "?"
    return Regex("""\{([^}]+)\}""").replace(pattern) { m ->
        val name = m.groupValues[1]
        if (args.containsKey(name)) args[name]?.toString() ?: "" else m.value
    }
}
