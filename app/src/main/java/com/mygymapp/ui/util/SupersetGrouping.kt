package com.mygymapp.ui.util

/** A superset chain may hold at most this many consecutive exercises. */
const val MAX_SUPERSET_SIZE = 3

/**
 * Walks [items] left-to-right, collecting maximal runs of consecutive elements where
 * [isPairedWithNext] is true for every element except the last. Calls [single] for a
 * standalone element and [group] with the run's indices for a chain of 2 or more.
 *
 * The run length is capped at [MAX_SUPERSET_SIZE]: a longer chain of `true` flags (which
 * the routine editor never produces — see
 * [RoutineEditViewModel.toggleSuperset][com.mygymapp.ui.screen.routineedit.RoutineEditViewModel]
 * — but a hand-edited YAML could) is split, and the trailing `true` flag on the boundary
 * element is simply ignored. The cap lives here, on the grouping, not on the persisted flag.
 *
 * Used by both [RoutineEditViewModel][com.mygymapp.ui.screen.routineedit.RoutineEditViewModel]
 * (segments of a routine being edited) and
 * [ActiveRoutineScreen][com.mygymapp.ui.screen.activeroutine] (groups of exercises
 * in an active session). They produce different sealed classes but the grouping
 * logic is the same.
 */
inline fun <T, R> groupSupersets(
    items: List<T>,
    isPairedWithNext: (T) -> Boolean,
    single: (index: Int) -> R,
    group: (indices: List<Int>) -> R,
): List<R> {
    val result = mutableListOf<R>()
    var start = 0
    while (start < items.size) {
        // Extend the run while the boundary element links forward and the cap allows it.
        var end = start
        while (end - start + 1 < MAX_SUPERSET_SIZE &&
            end + 1 < items.size &&
            isPairedWithNext(items[end])
        ) {
            end++
        }
        result.add(if (end == start) single(start) else group((start..end).toList()))
        start = end + 1
    }
    return result
}
