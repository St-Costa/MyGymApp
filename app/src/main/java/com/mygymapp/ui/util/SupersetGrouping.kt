package com.mygymapp.ui.util

/**
 * Walks [items] left-to-right, pairing each element with the next one when
 * [isPairedWithNext] returns true for it. Calls [single] for standalone elements
 * and [pair] for matched pairs, collecting the results.
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
    pair: (index1: Int, index2: Int) -> R,
): List<R> {
    val result = mutableListOf<R>()
    var i = 0
    while (i < items.size) {
        if (isPairedWithNext(items[i]) && i + 1 < items.size) {
            result.add(pair(i, i + 1))
            i += 2
        } else {
            result.add(single(i))
            i++
        }
    }
    return result
}
