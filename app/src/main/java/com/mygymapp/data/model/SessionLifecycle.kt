package com.mygymapp.data.model

/**
 * Explicit disk-side lifecycle of a session (ISM-01).
 *
 * Before this type existed the envelope state was reconstructed ad hoc at every
 * call site from `completedAt` + `sessionRpe` (+ the RAM-only `sessionFinalized`
 * in `ActiveRoutineViewModel`): 20 `completedAt.isBlank/isNotBlank` checks in 9
 * files, with the legal combinations living only in `docs/CONVENTIONS.md` prose.
 * The mapping below is the single definition of "what state is this session in
 * on disk" — `WorkoutRepository.isGhostSession` and the boot-sweep delegate to
 * it, so the ghost rule ("blank `completedAt` ⇒ ghost, no matter how much was
 * logged") can no longer drift between readers.
 *
 * Disk format is unchanged: this is a pure view over the existing fields, so no
 * migration is needed. What is NOT modelled here (yet): the in-memory flow flags
 * of `ActiveRoutineViewModel` (`sessionFinalized`, `sessionRegistered`,
 * `showRpePrompt`, `sessionRegisteredEvent`) — those stay RAM-side and are the
 * natural next step (see `docs/IMPLICIT_STATE_MACHINES.md` ISM-01).
 */
sealed interface SessionLifecycle {
    /** `completedAt` blank: never finalized. Deleted on exit/boot, never synced. */
    data object Ghost : SessionLifecycle

    /**
     * `completedAt` stamped, RPE not yet answered. Durable (charts + sidecars
     * merged) but not yet registered/synced — the crash window this state names:
     * a kill between `finalizeSession` and Registra lands exactly here.
     */
    data object FinalizedUnregistered : SessionLifecycle

    /** `completedAt` stamped and RPE answered: the terminal on-disk state. */
    data class Registered(val rpe: Int) : SessionLifecycle
}

/** The single definition of a session's on-disk state. See [SessionLifecycle]. */
fun WorkoutSession.lifecycle(): SessionLifecycle = when {
    completedAt.isBlank() -> SessionLifecycle.Ghost
    sessionRpe == null -> SessionLifecycle.FinalizedUnregistered
    else -> SessionLifecycle.Registered(sessionRpe)
}

val SessionLifecycle.isGhost: Boolean get() = this is SessionLifecycle.Ghost

/** True once `completedAt` is stamped, whether or not the RPE was answered. */
val SessionLifecycle.isFinalizedOnDisk: Boolean get() = this !is SessionLifecycle.Ghost

/** Finalize (`finalizeSession`) is legal only from [SessionLifecycle.Ghost]. */
fun SessionLifecycle.canFinalize(): Boolean = this is SessionLifecycle.Ghost

/** Register (RPE prompt → sync enqueue) is legal only from [SessionLifecycle.FinalizedUnregistered]. */
fun SessionLifecycle.canRegister(): Boolean = this is SessionLifecycle.FinalizedUnregistered

/**
 * Explicit lifecycle of one exercise slot in a session (ISM-02).
 *
 * Before this type existed the slot state was reconstructed from
 * `completed + completedEmpty + substitutedFor` at every call site
 * (`isUntouched`, `isSwitchEligible`, `withExerciseSwitched`,
 * `withExercisesStarted`, tonnage/history/PR filters). Those predicates now
 * delegate here, so "what does this combination mean" is defined once.
 *
 * Defensive rule: the illegal combination `completed == false +
 * completedEmpty == true` (invariant: empty ⇒ completed) maps to [Open] —
 * an uncompleted slot is open no matter what the stale flag says, matching
 * what every previous reader did with it (`isUntouched() == true`,
 * still switch-eligible subject to the recorded-sets guard).
 */
sealed interface SlotLifecycle {
    /** The originally-planned id when this slot was switch-created, else null. */
    val switchedFrom: String?

    /** Never completed (or illegal completed=false + completedEmpty=true): still on the active list. */
    data class Open(override val switchedFrom: String?) : SlotLifecycle

    /** Completed with real input: represents performed work. */
    data class Performed(override val switchedFrom: String?) : SlotLifecycle

    /** Completed without touching anything: closed out, but not performed work. */
    data class Skipped(override val switchedFrom: String?) : SlotLifecycle
}

/** The single definition of a slot's state. See [SlotLifecycle]. */
fun WorkoutExercise.slotLifecycle(): SlotLifecycle = when {
    !completed -> SlotLifecycle.Open(substitutedFor)
    completedEmpty -> SlotLifecycle.Skipped(substitutedFor)
    else -> SlotLifecycle.Performed(substitutedFor)
}

val SlotLifecycle.isOpen: Boolean get() = this is SlotLifecycle.Open

/** Closed out of the active list, performed or not ([SlotLifecycle.Performed]/[SlotLifecycle.Skipped]). */
val SlotLifecycle.isClosed: Boolean get() = !isOpen

val SlotLifecycle.isSwitched: Boolean get() = switchedFrom != null

/** True only for [SlotLifecycle.Performed]: the only state tonnage / history / PR math may consume. */
val SlotLifecycle.representsPerformedWork: Boolean get() = this is SlotLifecycle.Performed

/**
 * An explicit Complete tap may land on [SlotLifecycle.Open] (normal close) or
 * [SlotLifecycle.Skipped] (re-complete un-skips it); never on [SlotLifecycle.Performed].
 * Mirrors `ActiveExerciseUi.isReopenable()`.
 */
fun SlotLifecycle.canComplete(): Boolean = this is SlotLifecycle.Open || this is SlotLifecycle.Skipped

/**
 * "Switch exercise" is allowed only before anything is recorded in this slot,
 * and only once per slot per session. [hasNoRecordedSets] is passed in (it
 * needs the sets, which the state doesn't carry) — see
 * [WorkoutExercise.hasNoRecordedSets].
 */
fun SlotLifecycle.canSwitch(hasNoRecordedSets: Boolean): Boolean =
    this is SlotLifecycle.Open && switchedFrom == null && hasNoRecordedSets

/** True when no slot is still on the active list (performed + skipped both count as closed). */
fun WorkoutSession.isAllClosed(): Boolean =
    exercises.all { it.slotLifecycle() !is SlotLifecycle.Open }
