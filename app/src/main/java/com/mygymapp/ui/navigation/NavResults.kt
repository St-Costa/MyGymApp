package com.mygymapp.ui.navigation

/**
 * Cross-screen result protocol over `savedStateHandle` (ISM-04).
 *
 * Exercise screens never mutate the active-routine list directly: they leave a
 * result under one of these keys on the previous back-stack entry, which
 * `AppNavigation` observes and forwards to `ActiveRoutineViewModel`. The four
 * keys have deliberately different shapes, because they answer different
 * "did anything get lost" questions:
 *
 * - [NavResultKeys.COMPLETED_EXERCISE_ID] — single slot. One exercise
 *   completed; overwriting is correct (a slot completes once per visit).
 * - [NavResultKeys.COMPLETED_SUPERSET_IDS] — comma-joined list, single slot.
 *   One chain completed together ([encodeSupersetResult]/[parseSupersetResult]
 *   round-trip it; every member is forwarded to `markExerciseCompleted`).
 * - [NavResultKeys.SWITCHED_EXERCISE_IDS] — **queue**, not a slot. Two
 *   switches before returning to the list must not overwrite each other:
 *   `"old,new"` pairs joined by `";"`, applied in order
 *   ([appendSwitchResult]/[parseSwitchResults]; malformed pairs are skipped,
 *   not crashed on).
 * - [NavResultKeys.PICKED_EXERCISE_ID] (+ [NavResultKeys.pickedSideKey] per
 *   superset side) — single slot. The filtered picker's choice, consumed by
 *   the exercise screen that opened it.
 *
 * Consume protocol (every key): observe reactively via
 * `getStateFlow(key, null)` — never a one-shot `get()` + `LaunchedEffect(key)`,
 * which misses results that land without a recompose — apply, then `remove()`
 * the key so a recomposition never re-applies it. See the `AppNavigation`
 * KDoc (Sprint 3 rules) and `SwitchResultQueueTest`.
 */
object NavResultKeys {
    const val COMPLETED_EXERCISE_ID = "completedExerciseId"
    const val COMPLETED_SUPERSET_IDS = "completedSupersetIds"
    const val SWITCHED_EXERCISE_IDS = "switchedExerciseIds"
    const val PICKED_EXERCISE_ID = "pickedExerciseId"

    /**
     * Per-member picker key (`pickedExerciseIdSide1..N`): a switch on one
     * superset member can never be misapplied to another member's slot.
     * Superset VM and picker route agree on 1-based sides (see
     * `Screen.ExercisePicker.createRoute(..., resultKeySide)`).
     */
    fun pickedSideKey(side: Int): String = "pickedExerciseIdSide$side"
}

/** Appends one `"old,new"` switch pair to the queue (never overwrites). */
internal fun appendSwitchResult(existing: String?, oldId: String, newId: String): String =
    if (existing.isNullOrBlank()) "$oldId,$newId" else "$existing;$oldId,$newId"

/** Parses the switch queue back into ordered pairs; malformed pairs are skipped. */
internal fun parseSwitchResults(value: String): List<Pair<String, String>> =
    value.split(";").mapNotNull { pair ->
        val parts = pair.split(",", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0] to parts[1]
        } else {
            null
        }
    }

/** Encodes one completed chain for [NavResultKeys.COMPLETED_SUPERSET_IDS]. */
internal fun encodeSupersetResult(exerciseIds: List<String>): String =
    exerciseIds.joinToString(",")

/** Decodes [NavResultKeys.COMPLETED_SUPERSET_IDS]; every entry is forwarded. */
internal fun parseSupersetResult(value: String): List<String> = value.split(",")
