package com.mygymapp.ui.screen.superset

import com.mygymapp.data.model.ExerciseType

/**
 * Duration of the shared countdown-loop timer in a superset: the first STRETCH set's
 * duration (like the standalone stretch screen, which runs off the first set's
 * `timeSeconds` — all sets of a routine exercise share the routine's `timePerSetSeconds`),
 * with the same 60 s fallback when the chain has no STRETCH member. Pure, unit-tested.
 */
internal fun supersetCountdownDuration(sets: List<SupersetSetUi>): Int =
    sets.firstOrNull { it.exerciseType == ExerciseType.STRETCH }
        ?.timeSeconds?.coerceAtLeast(1) ?: 60
