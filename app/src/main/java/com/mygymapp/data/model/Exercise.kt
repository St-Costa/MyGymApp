package com.mygymapp.data.model

data class Exercise(
    val id: String,
    val name: String,
    val type: ExerciseType,
    val bodypart: String,
    val link: String = "",
    val notes: String = "",
    val defaultRepRangeMin: Int = 8,
    val defaultRepRangeMax: Int = 12,
    val created: String = "",
    val updated: String = "",
    // FORZA only: no external weight by design (e.g. plank, push-ups, mobility work).
    // Distinguishes "genuinely bodyweight, weight=0 is correct" from "set never touched,
    // weight=0 by default" — without this, tonnage/PR/e1RM analysis (phone-side and
    // server-side, see ANALYSIS_SPEC.md §1.2-1.5) silently drops all bodyweight work
    // because it filters on weight > 0. Propagated onto each WorkoutExercise's sets when
    // a session is built from this exercise — see WorkoutExercise/ExerciseSet.Strength.
    // Kept as a stored field (rather than derived from loadMode) so legacy files and
    // existing callers keep working unchanged; ExerciseParser keeps it in sync with
    // loadMode == LoadMode.BODYWEIGHT on both read and write.
    val isBodyweight: Boolean = false,
    // How much of the lifter's body weight this movement actually loads, as a percent —
    // one of 25 / 50 / 75 / 100 (squat ≈ 100, plank/push-up ≈ 75, reverse sit-up ≈ 50,
    // tibialis raise ≈ 25). Only meaningful when isBodyweight is true, and then it is
    // mandatory: a bodyweight exercise always carries one of the four values (legacy files
    // with no value migrate to 75 in ExerciseParser). At exercise-completion time each set's
    // `weight` is materialized to bwLoadPercent/100 * bodyWeightKg so all downstream tonnage/
    // PR/e1RM math (phone and server) keeps working unchanged on `reps * weight`.
    val bwLoadPercent: Int = 0,
    // How this exercise's set weight is loaded: MANUAL (plate/dumbbell load entered directly),
    // BODYWEIGHT (mirrors isBodyweight/bwLoadPercent above), or ASSISTED — an assisted machine
    // (e.g. an assisted pull-up/dip station) where the number set on the machine is *subtracted*
    // from body weight rather than added: more assistance selected = less real load, so a lower
    // number on the machine is the actual progress. Mutually exclusive with isBodyweight; there
    // is no per-exercise assist amount (unlike bwLoadPercent) because the assist offset varies
    // set-to-set like a normal weight, not a fixed property of the movement — it is entered at
    // set-completion time and materialized the same way, see materializeAssistedWeight() in
    // TonnageMath.kt and ExerciseSet.Strength.assistOffsetKg.
    val loadMode: LoadMode = LoadMode.MANUAL,
)

enum class LoadMode {
    MANUAL,
    BODYWEIGHT,
    ASSISTED,
}

enum class ExerciseType {
    FORZA,
    STRETCH,
    CARDIO;

    companion object {
        fun fromString(value: String): ExerciseType =
            when (value.lowercase()) {
                "stretch" -> STRETCH
                "cardio" -> CARDIO
                else -> FORZA
            }
    }

    fun toFileString(): String = when (this) {
        FORZA -> "forza"
        STRETCH -> "stretch"
        CARDIO -> "cardio"
    }
}
