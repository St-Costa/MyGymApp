package com.mygymapp.data.model

data class WorkoutSession(
    val id: String,
    val routineId: String,
    val routineName: String,
    val date: String,
    val startedAt: String = "",
    val completedAt: String = "",
    val totalTonnage: Double = 0.0,
    val tonnageByBodypart: Map<String, Double> = emptyMap(),
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val vo2max: Double = 0.0,
    // ECG-derived metrics (post-session analysis, Step B)
    val ecgBeats: Int = 0,
    val ecgDurationSec: Double = 0.0,
    val ecgAvgHr: Double = 0.0,
    val ecgSessionRmssd: Double = 0.0,
    val ecgPacCount: Int = 0,
    val ecgPauseCount: Int = 0,
    val ecgIrregularBeats: Int = 0,
    val cardiacDriftBpmMin: Double = 0.0,
    // Extended HRV + recovery + screening
    val restingHr: Int = 0,             // from readiness 60s
    val hrr60s: Double = 0.0,           // average HR drop 60s after peaks (BPM)
    val sdnn: Double = 0.0,             // overall HRV (ms)
    val pnn50: Double = 0.0,            // % of RR pairs with >50ms diff
    val poincareSd1: Double = 0.0,      // short-term (vagal) scatter (ms)
    val poincareSd2: Double = 0.0,      // long-term scatter (ms)
    val poincareRatio: Double = 0.0,    // SD2/SD1 sympathovagal balance
    val afibSuspicionEpisodes: Int = 0, // sustained irregular segments
    // Session-RPE (Foster method): subjective "how hard was this session" 0-9, asked right
    // after the session ends via a mandatory (non-skippable) prompt. Null only transiently,
    // before the prompt is answered — abandoned/ghost sessions never reach registration, so
    // no completed session persists without one. Complements totalTonnage (external load)
    // as the internal-load signal the server's ACWR monitoring uses to validate/enrich
    // itself. See docs/SYNC.md.
    val sessionRpe: Int? = null,
    // sessionRpe * session duration in minutes (Foster's session-load method). Null unless
    // both sessionRpe and a valid startedAt/completedAt pair are available.
    val sessionLoad: Float? = null,
    val exercises: List<WorkoutExercise> = emptyList(),
)

data class WorkoutExercise(
    val exerciseId: String,
    val exerciseName: String,
    val bodypart: String,
    val type: ExerciseType,
    val completed: Boolean = false,
    /**
     * Wall-clock window of this slot's last visit, stamped when the exercise screen opens
     * ([withExercisesStarted]) and when the slot is closed ([completedAt]). Together they give
     * the active-routine section estimates a real duration per exercise — including the rest
     * between sets and, for stretching, the recovery time between holds, not just the
     * configured hold seconds. Empty on sessions saved before these fields existed; readers
     * must treat a missing/invalid pair as "no measurement" and fall back to configured
     * durations.
     */
    val startedAt: String = "",
    val completedAt: String = "",
    val sets: List<ExerciseSet> = emptyList(),
    /** Warmup or fixed-daily exercise: still recorded, but excluded from all tonnage math. */
    val excludeFromTonnage: Boolean = false,
    /**
     * True when this exercise was performed as a fixed-daily exercise in this session.
     * Used so daily progress is compared only against prior sessions where it was also
     * daily (and normal progress only against prior normal sessions).
     */
    val isDaily: Boolean = false,
    /**
     * True when the lifter tapped "Complete" without ever touching a pre-filled value (see
     * docs/CONVENTIONS.md "Untouched-exercise guard"). The exercise IS marked completed — it
     * closes out of the active list like any other — and its `sets` still carry the grey
     * pre-fill that was on screen (persisted so the next session's prefill walk-back finds
     * real numbers, not 0x0 — see CHANGELOG Phase 74). But it does not represent performed
     * work: the active-routine row shows the neutral "skipped" styling (grey border + X icon)
     * instead of a tonnage change, and all tonnage / history / PR math skips it.
     */
    val completedEmpty: Boolean = false,
    /**
     * Set when this slot was swapped mid-session for a different exercise of the same
     * bodypart+type via "Switch exercise" (see docs/CONVENTIONS.md#switch-exercise). Holds the
     * originally-planned exerciseId — the slot itself now carries the NEW exercise's id/name/
     * bodypart/type/sets. This is purely a per-session record: the routine on disk is never
     * touched, so the next session from the same routine proposes the original exercise again.
     * Null for every exercise that was never switched (the overwhelming majority).
     */
    val substitutedFor: String? = null,
) {
    /**
     * True when this exercise does not represent performed work — either the lifter never
     * tapped "Complete" for it (`completed == false`) or they tapped it without touching any
     * value (`completedEmpty == true`; see docs/CONVENTIONS.md "Untouched-exercise guard").
     * In both cases `sets` may be non-empty — they carry the grey pre-fill that was on screen
     * — but tonnage / history / PR math must skip the exercise. Callers that only want the
     * "still open in the active list" case check `!completed` directly.
     */
    fun isUntouched(): Boolean = slotLifecycle() !is SlotLifecycle.Performed

    /**
     * True when no set in this slot carries any real data yet — same "did the lifter actually
     * start this" check used by the ghost-session guard (see
     * docs/CONVENTIONS.md#ghost-session-prevention) and reused here as the switch-exercise
     * eligibility guard, so both stay in sync by construction instead of by two copies of the
     * same per-set-type logic drifting apart.
     */
    fun hasNoRecordedSets(): Boolean = sets.none { set ->
        when (set) {
            is ExerciseSet.Strength -> set.reps > 0 || set.weight > 0.0
            is ExerciseSet.Stretch -> set.done
            is ExerciseSet.Cardio -> set.startedAt.isNotBlank()
        }
    }

    /**
     * "Switch exercise" is allowed only before the lifter has recorded anything in this slot,
     * and only once per slot per session — once switched (or once real data exists), the slot
     * is locked for the rest of this session. See docs/CONVENTIONS.md#switch-exercise.
     */
    fun isSwitchEligible(): Boolean = slotLifecycle().canSwitch(hasNoRecordedSets())

    /**
     * Which of the three mutually-exclusive slot categories this exercise was performed as.
     * The same exercise can appear across history in more than one category (e.g. as a
     * fixed-daily entry and also inside a routine), and their load histories are unrelated —
     * "previous" pre-fills and the all-time tonnage PR must both compare like-with-like, so
     * they filter prior sessions by matching [slotContext]. See docs/CONVENTIONS.md.
     */
    val slotContext: SlotContext
        get() = when {
            isDaily -> SlotContext.DAILY
            excludeFromTonnage -> SlotContext.WARMUP
            else -> SlotContext.NORMAL
        }
}

/** The three mutually-exclusive kinds of exercise slot in a session. See [WorkoutExercise.slotContext]. */
enum class SlotContext { NORMAL, WARMUP, DAILY }

/**
 * Replaces the slot currently holding [oldExerciseId] with [newExercise] — see
 * docs/CONVENTIONS.md#switch-exercise. Returns `this` unchanged if the slot doesn't exist or
 * isn't eligible (see [WorkoutExercise.isSwitchEligible]), so callers can check the result
 * for "did anything change" instead of duplicating the eligibility check themselves.
 *
 * When [allowDiscardRecordedSets] is true the recorded-sets guard is skipped: a slot whose
 * on-disk sets already carry data can still be switched, discarding that partial work. The
 * exercise ViewModels pass `true` exactly when the current visit is untouched (the Complete
 * button still reads "Salta"), so a slot locked by an earlier accidental
 * touch can still be recovered — the swap itself is the explicit confirmation that the
 * partial work may go. Completed and already-switched slots stay locked either way.
 *
 * The slot keeps its position in [WorkoutSession.exercises] and its warmup/daily/tonnage
 * flags (switch is offered for NORMAL and fixed-daily slots only — never warmup) — only
 * exerciseId/exerciseName/bodypart/type/sets change, plus `substitutedFor` recording the
 * original id for history/sync. Shared by ActiveRoutineViewModel and the three exercise
 * ViewModels so the mutation logic lives in exactly one place.
 */
fun WorkoutSession.withExerciseSwitched(
    oldExerciseId: String,
    newExercise: Exercise,
    allowDiscardRecordedSets: Boolean = false,
): WorkoutSession {
    val slot = exercises.find { it.exerciseId == oldExerciseId } ?: return this
    val slotState = slot.slotLifecycle()
    if (slotState !is SlotLifecycle.Open || slotState.switchedFrom != null) return this
    if (!allowDiscardRecordedSets && !slot.hasNoRecordedSets()) return this
    // Regenerate empty sets matching the new exercise's type (same shape the session-creation
    // path in ActiveRoutineViewModel.init builds for a freshly-started slot). STRETCH defaults
    // to 60s per set — mirrors StretchExerciseViewModel's own fallback when no sets exist yet
    // — rather than 0s, which would show a stopwatch target of zero.
    val newSets = when (newExercise.type) {
        ExerciseType.FORZA -> slot.sets.map { ExerciseSet.Strength(isBodyweight = newExercise.isBodyweight) }
        ExerciseType.STRETCH -> slot.sets.map { ExerciseSet.Stretch(timeSeconds = 60) }
        ExerciseType.CARDIO -> emptyList()
    }
    val newSlot = slot.copy(
        exerciseId = newExercise.id,
        exerciseName = newExercise.name,
        bodypart = newExercise.bodypart,
        type = newExercise.type,
        // The new exercise is a new visit: its screen stamps a fresh startedAt when it opens.
        // Carrying the replaced slot's window over would bill the new exercise for the old one's
        // time in the section estimates.
        startedAt = "",
        completedAt = "",
        sets = newSets,
        substitutedFor = oldExerciseId,
    )
    return copy(exercises = exercises.map { if (it.exerciseId == oldExerciseId) newSlot else it })
}

/**
 * Back-navigation ("save as incomplete") write for a single slot: replaces [sets] but preserves
 * the slot's entry completion flags exactly as they were on disk — an open slot stays open, and
 * a completed-empty (skipped) slot the lifter re-entered to look around and backed out of stays
 * skipped. Only an explicit Complete re-closes the slot (see docs/CONVENTIONS.md "Re-entering a
 * skipped exercise"). Returns `this` unchanged when the slot doesn't exist. Pure, unit-tested.
 */
fun WorkoutSession.withExerciseSetsOnExit(
    exerciseId: String,
    sets: List<ExerciseSet>,
): WorkoutSession {
    if (exercises.none { it.exerciseId == exerciseId }) return this
    return copy(
        exercises = exercises.map {
            if (it.exerciseId == exerciseId) it.copy(sets = sets) else it
        },
    )
}

/**
 * Stamps the start of a visit for one or more slots (a superset screen owns 2–3 at once). Every
 * open **restarts** the window: the exercise screen can be left and reopened, and the idle time
 * between visits is not exercise time. A performed-and-completed slot is never reopened (only
 * skipped slots are — see [WorkoutExercise.isSwitchEligible]) and is left untouched defensively.
 * Clearing [WorkoutExercise.completedAt] on a reopened skipped slot avoids a `startedAt >
 * completedAt` pair that no duration reader could use. Pure, unit-tested.
 */
fun WorkoutSession.withExercisesStarted(
    exerciseIds: Collection<String>,
    at: String,
): WorkoutSession {
    val ids = exerciseIds.toSet()
    if (ids.isEmpty()) return this
    var changed = false
    val updated = exercises.map { ex ->
        if (ex.exerciseId in ids && ex.slotLifecycle() !is SlotLifecycle.Performed) {
            changed = true
            ex.copy(startedAt = at, completedAt = "")
        } else {
            ex
        }
    }
    return if (changed) copy(exercises = updated) else this
}

/** Single-slot convenience overload of [withExercisesStarted]. */
fun WorkoutSession.withExerciseStarted(exerciseId: String, at: String): WorkoutSession =
    withExercisesStarted(listOf(exerciseId), at)

/**
 * Measured wall-clock duration of this slot's last visit, or null when there is no usable
 * measurement (legacy session, skipped slot, malformed/negative window). This is what the
 * active-routine section estimate and the stretch row badge use: it includes the rest/recovery
 * time between sets, unlike the configured `timePerSetSeconds` sums.
 *
 * Cardio prefers the sum of its closed blocks — a block's start/end is the actual ride, while
 * the screen window may include waiting around before "Inizia". Falls back to the exercise
 * window when block timestamps are missing/invalid (imported or old sessions).
 */
fun WorkoutExercise.measuredDurationSeconds(): Int? {
    if (isUntouched()) return null
    if (type == ExerciseType.CARDIO) {
        val blockSeconds = sets
            .filterIsInstance<ExerciseSet.Cardio>()
            .filter { it.startedAt.isNotBlank() && it.endedAt.isNotBlank() }
            .sumOf { durationSeconds(it.startedAt, it.endedAt) }
        if (blockSeconds > 0) return blockSeconds.coerceAtMost(MAX_MEASURED_SECONDS)
    }
    return measuredWindowSeconds(startedAt, completedAt)
}

/**
 * Measured wall-clock duration of the whole session, or null when the envelope is missing,
 * malformed, reversed, or implausibly long. The section estimate uses it to reconcile the
 * per-exercise windows with the time actually spent: screens opened after the first set (or
 * any other unmeasured pause) otherwise make the sum of the slots shorter than the session.
 */
fun WorkoutSession.measuredSessionDurationSeconds(): Int? =
    measuredWindowSeconds(startedAt, completedAt)

private fun measuredWindowSeconds(startText: String, endText: String): Int? {
    val start = parseLocalDateTime(startText) ?: return null
    val end = parseLocalDateTime(endText) ?: return null
    val seconds = java.time.Duration.between(start, end).seconds
    return seconds
        .takeIf { it in 1..MAX_MEASURED_SECONDS.toLong() }
        ?.toInt()
}

/**
 * Plausibility ceiling for one slot's or a whole session's measured duration (6 hours). A
 * corrupt clock or a session left open overnight must not turn a section estimate into a
 * nonsense number.
 */
private const val MAX_MEASURED_SECONDS = 6 * 60 * 60

private fun parseLocalDateTime(text: String): java.time.LocalDateTime? =
    runCatching { java.time.LocalDateTime.parse(text) }.getOrNull()

private fun durationSeconds(startText: String, endText: String): Int {
    val start = parseLocalDateTime(startText) ?: return 0
    val end = parseLocalDateTime(endText) ?: return 0
    return java.time.Duration.between(start, end).seconds
        .coerceIn(0, MAX_MEASURED_SECONDS.toLong())
        .toInt()
}
