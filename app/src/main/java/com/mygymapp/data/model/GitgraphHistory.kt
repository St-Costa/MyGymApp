package com.mygymapp.data.model

/**
 * Pre-computed home-screen gitgraph history — the 4 week-rows (28 day squares) *before* the
 * current week. Cached in `history/_gitgraph.yaml` and refreshed by
 * [WorkoutRepository][com.mygymapp.data.repository.WorkoutRepository] as the window slides
 * forward and as completed sessions land.
 *
 * ## Why this is cacheable
 * A day square shows an [DayCellStatus] + tonnage-% (or cardio minutes) derived from that
 * day's registered session and the previous session of the same routine. Both are immutable
 * once registered, so a square for a day *before the current week* never changes. Only the
 * current week's squares (rendered from a separate, small `currentWeekSessions` query) move
 * day to day — those are not in this cache.
 *
 * ## Consistency
 * - **Window slide**: the visible window is "the 4 weeks before the Monday of the current
 *   week". When today crosses into a new week, [windowStartMonday] no longer matches and the
 *   reader recomputes just the newly-exposed days (a narrow date-range parse), rotates them
 *   in, and rewrites.
 * - **New session**: [WorkoutRepository.save] of a completed session dated within the cached
 *   window recomputes that one day (normally a no-op — sessions are registered *today*, which
 *   is the current week, outside this window).
 * - **Delete / debug-seed / schema bump**: the whole cache is dropped and lazily rebuilt on
 *   next read.
 */
data class GitgraphHistory(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** ISO date of the Monday that starts the oldest (first) of the 4 week-rows. */
    val windowStartMonday: String = "",
    /** Exactly 28 entries, oldest day first (row-major: week0 Mon..Sun, week1 Mon..Sun, …). */
    val days: List<GitgraphDay> = emptyList(),
) {
    fun isComplete(): Boolean = days.size == DAY_COUNT

    companion object {
        const val DAY_COUNT = 28

        /**
         * Bump when the meaning of a stored field changes (how status/% is derived, added
         * fields). A cache whose `schemaVersion` differs is ignored and rebuilt.
         *
         * v2: adds `stretchMinutes` and uses display fallback priority tonnage-% → stretching →
         *     cardio.
         * v1: `status` = IMPROVED when the day's common-exercise tonnage ≥ the same routine's
         *     previous session's (or it's the first session of that routine), else REGRESSED;
         *     NONE when the day has no session. `tonnageChangePct` = signed % vs that previous
         *     session, null when there's no comparison or previous tonnage is 0.
         *     `cardioMinutes` = closed cardio-block minutes, only when `tonnageChangePct` is null.
         *     Matches MainViewModel.computeDayCell at the time of writing.
         */
        const val SCHEMA_VERSION = 2
    }
}

/** One day square. A day with no registered session is [status] = NONE and all else null. */
data class GitgraphDay(
    val date: String,                       // ISO date, always set
    val status: DayCellStatus = DayCellStatus.NONE,
    val tonnageChangePct: Double? = null,
    val stretchMinutes: Int? = null,
    val cardioMinutes: Int? = null,
    val routineName: String? = null,
    val sessionId: String? = null,
)

/** Mirrors the UI's `DayStatus` (kept in the data layer so the calculator/parser don't depend on UI). */
enum class DayCellStatus { NONE, IMPROVED, REGRESSED }
