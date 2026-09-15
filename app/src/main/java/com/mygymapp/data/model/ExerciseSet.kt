package com.mygymapp.data.model

sealed class ExerciseSet {
    data class Strength(
        val reps: Int = 0,
        val weight: Double = 0.0,
        // Copied from Exercise.isBodyweight when the set is built (see
        // ActiveRoutineViewModel/StrengthExerciseViewModel/SupersetViewModel). Marks a
        // set as genuinely bodyweight work (weight=0 by nature, e.g. plank) rather than
        // an untouched/never-filled set (weight=0 by default) — analysis that filters on
        // weight > 0 (tonnage, PR, e1RM) must check this before excluding a set.
        val isBodyweight: Boolean = false,
        // Audit trail for a materialized bodyweight set. On exercise completion, a bodyweight
        // set's `weight` is filled in as bwLoadPercent/100 * bwBaseWeightKg (rounded to 0.5 kg)
        // so `reps * weight` tonnage/PR/e1RM keeps working everywhere with no special-casing.
        // These two fields record how that number was reached — the Exercise.bwLoadPercent in
        // effect at the time, and the body weight taken from the most recent scale weigh-in on
        // or before the session date. Both 0 when the set is not a materialized bodyweight set
        // (or when no weigh-in was available, in which case `weight` stays 0). See
        // materializeBodyweightWeight() in TonnageMath.kt and docs/SYNC.md.
        val bwLoadPercent: Int = 0,
        val bwBaseWeightKg: Double = 0.0,
        // Assisted-machine counterpart of isBodyweight/bwLoadPercent above: marks a set as
        // recorded on an assisted machine (e.g. assisted pull-up/dip station), where the raw
        // number set on the machine is the *assistance* given, not the load — more assistance
        // means less real load. On exercise completion an assisted set's `weight` is filled in
        // as bwBaseWeightKg - assistOffsetKg (materializeAssistedWeight() in TonnageMath.kt), so
        // `reps * weight` tonnage/PR/e1RM keeps working everywhere with no special-casing, same
        // as the bodyweight case. Mutually exclusive with isBodyweight.
        val isAssisted: Boolean = false,
        // The number set on the assist machine (audit trail, mirrors bwLoadPercent's role for
        // bodyweight sets) and the body weight used to materialize it (reuses bwBaseWeightKg,
        // taken from the most recent scale weigh-in on or before the session date — same source
        // as the bodyweight case). Both 0 when the set is not a materialized assisted set.
        val assistOffsetKg: Double = 0.0,
    ) : ExerciseSet()

    data class Stretch(
        val timeSeconds: Int = 0,
        val done: Boolean = false,
    ) : ExerciseSet()

    /**
     * One continuous cardio block (e.g. "10 min bike"). A cardio exercise can have several of
     * these in the same session (see docs/STORAGE.md) — each "Inizia cardio"/"Termina cardio"
     * cycle in CardioExerciseScreen appends one. [startedAt]/[endedAt] are absolute ISO
     * LocalDateTime strings, not offsets, so the sync server can correlate them against the raw
     * .ecg file's own startTimestamp+sampleRate header to slice out the matching waveform segment
     * without any change to the binary format (see docs/SYNC.md). Empty [endedAt] means the block
     * is still running (or was abandoned without an explicit "Termina cardio") — never persisted
     * with an exercise marked completed (see CardioExerciseViewModel.completeExercise()).
     * [avgHr]/[maxHr] are computed on-device from the live HR stream during the block, not
     * user-entered.
     */
    data class Cardio(
        val startedAt: String = "",
        val endedAt: String = "",
        val avgHr: Int = 0,
        val maxHr: Int = 0,
    ) : ExerciseSet()
}
