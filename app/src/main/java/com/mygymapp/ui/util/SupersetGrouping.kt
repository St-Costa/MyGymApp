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
/**
 * Removes the elements of [items] for which [keep] is false, and — crucially — stitches the
 * surviving members of a superset chain back together: a survivor keeps its forward link
 * ([linkSet] returns a copy with the link off) iff the next survivor in the output belongs to
 * the same original chain, i.e. every flag between the two was `true` in the source list.
 *
 * In practice: dropping a mid-chain member of a 3-chain (A links->B links->C, B removed)
 * yields A links->C — the daily section keeps a 2-superset instead of two singles. Dropping
 * the tail of a pair still singles the survivor (nothing left to pair with), and a survivor
 * whose chain boundary fell in the dropped span (A links->B | C links->D with B dropped — the
 * `false` flag on B ends A's chain) does NOT re-link across chains: A goes single, C->D stays.
 * The last survivor's link is always cleared so no pair spans a section boundary.
 *
 * This is what happens when a fixed-daily exercise that is also in the routine is filtered
 * out of the daily section mid-chain — see
 * [ActiveRoutineViewModel][com.mygymapp.ui.screen.activeroutine.ActiveRoutineViewModel].
 */
inline fun <T> filterStitchingSupersetLinks(
    items: List<T>,
    linked: (T) -> Boolean,
    linkOff: (T) -> T,
    keep: (T) -> Boolean,
): List<T> {
    val kept = items.indices.filter { keep(items[it]) }
    // For each survivor, does the original span up to the next survivor stay inside one chain?
    val keepLink = BooleanArray(kept.size)
    for (p in kept.indices) {
        if (p == kept.lastIndex) {
            keepLink[p] = false
        } else {
            val from = kept[p]
            val to = kept[p + 1]
            var continuous = true
            for (k in from until to) {
                if (!linked(items[k])) {
                    continuous = false
                    break
                }
            }
            keepLink[p] = continuous
        }
    }
    return kept.mapIndexed { p, i ->
        if (keepLink[p]) items[i] else if (linked(items[i])) linkOff(items[i]) else items[i]
    }
}

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
