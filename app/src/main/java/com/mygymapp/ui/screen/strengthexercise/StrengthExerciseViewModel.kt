package com.mygymapp.ui.screen.strengthexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.LoadMode
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.materializeAssistedWeight
import com.mygymapp.data.model.materializeBodyweightWeight
import com.mygymapp.data.model.repsForSameTonnage
import com.mygymapp.data.model.repsToBeatTonnage
import com.mygymapp.data.model.withExerciseSetsOnExit
import com.mygymapp.data.model.withExerciseStarted
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.Breadcrumbs
import com.mygymapp.ui.screen.exercise.ExerciseSessionViewModel
import com.mygymapp.ui.screen.exercise.mergeExitSets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StrengthSetUi(
    val reps: Int = 0,
    val weight: Double = 0.0,
    val previousReps: Int = 0,
    val previousWeight: Double = 0.0,
    val repsModified: Boolean = false,
    val weightModified: Boolean = false,
    val repsTouched: Boolean = false,
    val weightTouched: Boolean = false,
    /**
     * Tonnage (reps × weight) this set is being kept constant while the lifter changes the
     * weight: the reps are recomputed from it on every weight step, always rounded up, so
     * 50×10 → 60 becomes 60×9 instead of a dropped 60×8. Reset to 0 by a manual reps edit or
     * by confirming either value; the next weight change then re-anchors on whatever is on
     * screen.
     */
    val tonnageAnchor: Double = 0.0,
)

/**
 * Best single set ever recorded for this exercise, by tonnage (reps * weight).
 * [bwBaseWeightKg] is the body weight at the time it was logged for a bodyweight exercise
 * (0.0 otherwise / legacy) — bodyweight screens show `reps x bwBaseWeightKg` instead of
 * `reps x weight` (which for bodyweight is only bwLoadPercent% of the body weight).
 */
data class TonnagePr(
    val reps: Int,
    val weight: Double,
    val bwBaseWeightKg: Double = 0.0,
    val isAssisted: Boolean = false,
    val assistOffsetKg: Double = 0.0,
    /** Day key (`YYYY-MM-DD`) of the session this record comes from, "" when unknown. */
    val sessionDate: String = "",
)

/**
 * The single set ever recorded for this exercise with the highest estimated 1RM (Epley:
 * `weight * (1 + reps/30)`). Same fields as [TonnagePr] — the badge shows this set's
 * `reps x weight`, not the computed 1RM — but it is a *different* set: a heavy low-rep set
 * can hold this record without holding the tonnage one. [bwBaseWeightKg] is the body weight
 * at logging time for a bodyweight exercise (0.0 otherwise / legacy); the badge is hidden
 * for a bodyweight exercise when it's unknown, since the stored `weight` is materialized load.
 */
data class RmPr(
    val reps: Int,
    val weight: Double,
    val bwBaseWeightKg: Double = 0.0,
    val isAssisted: Boolean = false,
    val assistOffsetKg: Double = 0.0,
    /** Day key (`YYYY-MM-DD`) of the session this record comes from, "" when unknown. */
    val sessionDate: String = "",
)

data class StrengthExerciseUiState(
    val exercise: Exercise? = null,
    val sets: List<StrengthSetUi> = emptyList(),
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val description: String = "",
    val isLoading: Boolean = true,
    val allSetsFilled: Boolean = false,
    val tonnagePr: TonnagePr? = null,
    val rmPr: RmPr? = null,
    /**
     * Bodyweight only: reps to perform at the latest weigh-in to beat [tonnagePr]'s
     * tonnage, shown as ` [N]` after the badge (null when unknown — no weigh-in, legacy
     * PR without body weight, or non-bodyweight exercise).
     */
    val tonnagePrTargetReps: Int? = null,
    /** Day key (`YYYY-MM-DD`) of the session the grey pre-fill comes from, "" when none. Saved but intentionally not shown. */
    val previousSessionDate: String = "",
    /** Day keys (newest first) of the recent like-with-like sessions backing the pre-fill. Saved but intentionally not shown. */
    val previousHistoryDates: List<String> = emptyList(),
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): true only for a NORMAL or
    // fixed-daily slot with zero recorded sets so far — warmup/completed/already-switched
    // slots never show the button. excludeIds is every exerciseId already occupying a slot in
    // this session, passed to the filtered picker so it can never offer a duplicate.
    val switchEligible: Boolean = false,
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class StrengthExerciseViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
    private val workoutRepository: WorkoutRepository,
    private val scaleHistoryRepository: ScaleHistoryRepository,
) : ExerciseSessionViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(StrengthExerciseUiState())
    val uiState: StateFlow<StrengthExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            // The active session is always dated today; fetch it straight by id (filename
            // fast-path) instead of parsing every one of today's session files.
            var loadedSession = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
            // Start this slot's wall-clock window before showing the screen. Reopening a
            // skipped slot starts a fresh window (withExercisesStarted handles that) so idle
            // time between visits is not billed to the exercise in the section estimates.
            if (loadedSession != null) {
                val started = loadedSession.withExerciseStarted(
                    exerciseId,
                    java.time.LocalDateTime.now().toString(),
                )
                if (started !== loadedSession) loadedSession = workoutRepository.save(started)
            }
            val session = loadedSession
            currentSession = session

            val workoutExercise = session?.exercises?.find { it.exerciseId == exerciseId }
            // Whether this exercise is being performed as a fixed-daily exercise in this session.
            val isDaily = workoutExercise?.isDaily ?: false
            val isWarmup = (workoutExercise?.excludeFromTonnage ?: false) && !isDaily
            // Which of the three mutually-exclusive slot categories (normal / warmup / daily)
            // this exercise is in right now — "previous" pre-fills and the all-time PR both
            // compare only against prior sessions in the same category.
            val slotContext = workoutExercise?.slotContext
                ?: com.mygymapp.data.model.SlotContext.NORMAL

            // Get set count + rep range from the owning routine. Daily exercises live in the
            // fixed-daily routine, not the session's routine, so look them up there.
            // Looked up here (not just from workoutExercise.sets) because completing an exercise
            // without touching any value intentionally clears sets = emptyList() as a ghost-data
            // guard (see completeExercise()) — falling back to workoutExercise.sets.size alone
            // would leave the screen with zero set rows on re-entry.
            val rangeRoutineId =
                if (isDaily) com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID else session?.routineId ?: ""
            val routine = if (rangeRoutineId.isNotBlank()) routineRepository.getById(rangeRoutineId) else null
            val routineExercise = routine?.exercises?.find { it.exerciseId == exerciseId }

            val setCount = workoutExercise?.sets?.takeIf { it.isNotEmpty() }?.size
                ?: routineExercise?.sets
                ?: 3

            // Previous workout data + all-time PR for this exercise come from the per-exercise
            // stats sidecar (history/_stats/{id}.yaml) — a single small read instead of parsing
            // every session file that contains the exercise. Matched like-with-like on slot
            // context (daily / warmup / normal) AND on weighting approach (manual / bodyweight
            // / assisted, schema v5/v6) inside the sidecar; "previous" is the most recent
            // performed session (skips never advance it), with up to HISTORY_LIMIT earlier
            // ones behind it for the "penultima volta" fallback.
            val ctxStats = workoutRepository.getExerciseStats(exerciseId).forContext(slotContext)
            // The picker's "weight" always mirrors what the lifter actually dials in: manual
            // load, or the assist-machine number for an assisted exercise (never the
            // materialized net weight) — see PreviousSet.assistOffsetKg.
            val previousSets = ctxStats?.previousSetsFor(exercise.loadMode).orEmpty()
                .map {
                    ExerciseSet.Strength(
                        reps = it.reps,
                        weight = if (exercise.loadMode == LoadMode.ASSISTED) it.assistOffsetKg else it.weight,
                    )
                }

            // Rep range from the routine lookup above (routineExercise).
            val repMin = routineExercise?.repRangeMin ?: 0
            val repMax = routineExercise?.repRangeMax ?: 0

            // Use current session's in-progress values if available, else fall back to previous.
            // Checked per-set (not per-exercise): filling in set 1 shouldn't make sets 2-3 lose
            // their previous-session inheritance and drop to 0 while the lifter hasn't reached them yet.
            val currentSets = workoutExercise?.sets?.filterIsInstance<ExerciseSet.Strength>() ?: emptyList()

            // For sets beyond what the previous session recorded, fall back to the last
            // non-zero previous set so extra sets still inherit a sensible default.
            val lastMeaningfulPrev = previousSets.lastOrNull { it.reps > 0 || it.weight > 0.0 }

            val sets = (0 until setCount).map { i ->
                val prev = previousSets.getOrNull(i) ?: lastMeaningfulPrev
                val currCandidate = currentSets.getOrNull(i)
                val curr = currCandidate?.takeIf { it.reps > 0 || it.weight > 0.0 }
                StrengthSetUi(
                    reps = curr?.reps ?: prev?.reps ?: 0,
                    weight = curr?.weight ?: prev?.weight ?: 0.0,
                    previousReps = prev?.reps ?: 0,
                    previousWeight = prev?.weight ?: 0.0,
                    repsModified = curr != null && curr.reps != (prev?.reps ?: 0),
                    weightModified = curr != null && curr.weight != (prev?.weight ?: 0.0),
                )
            }

            // Process-death restore: keystrokes newer than the last disk save survive in
            // the SavedStateHandle — overlay them over the just-loaded sets. Previous-set
            // references and PRs always come from the fresh load above, never the draft.
            val draft = decodeStrengthDraft(
                savedStateHandle.get<ArrayList<String>>(DRAFT_STRENGTH_SETS),
            )
            val restoredSets = applyStrengthDraft(sets, draft)
            val loadModeForFill = exercise.loadMode
            val weightOptional =
                loadModeForFill == LoadMode.BODYWEIGHT || loadModeForFill == LoadMode.ASSISTED

            // All-time PRs (highest reps*weight set, and highest estimated-1RM set, in this
            // slot context) — also from the sidecar, same as "previous" above. The two can be
            // different sets; both badges show that set's reps x weight, RM on top.
            val tonnagePr = ctxStats?.pr?.let {
                TonnagePr(
                    reps = it.reps,
                    weight = it.weight,
                    bwBaseWeightKg = it.bwBaseWeightKg,
                    isAssisted = it.isAssisted,
                    assistOffsetKg = it.assistOffsetKg,
                    sessionDate = ctxStats.prSessionDate,
                )
            }
            val rmPr = ctxStats?.rmPr?.let {
                RmPr(
                    reps = it.reps,
                    weight = it.weight,
                    bwBaseWeightKg = it.bwBaseWeightKg,
                    isAssisted = it.isAssisted,
                    assistOffsetKg = it.assistOffsetKg,
                    sessionDate = ctxStats.rmPrSessionDate,
                )
            }
            val previousDate = ctxStats?.previousSessionDateFor(exercise.loadMode).orEmpty()
            val historyDates = ctxStats?.historyFor(exercise.loadMode).orEmpty().map { it.date }

            // Bodyweight only: how many reps at the latest weigh-in beat the tonnage PR.
            // The badge reads `reps x body-weight-then` (e.g. `10 x 77`); the suffix answers
            // "how many do I do now" — computed against today's weigh-in, which is also what
            // completion will materialize the load from (see buildStrengthSets).
            val tonnagePrTargetReps =
                if (exercise.loadMode == LoadMode.BODYWEIGHT && (tonnagePr?.bwBaseWeightKg ?: 0.0) > 0.0) {
                    val latestWeight = scaleHistoryRepository.getLatestWeightOnOrBefore(
                        java.time.LocalDate.now()
                    )
                    repsToBeatTonnage(tonnagePr!!.reps, tonnagePr.bwBaseWeightKg, latestWeight ?: 0.0)
                } else {
                    null
                }

            // Switch is offered for a NORMAL or fixed-daily slot (not warmup — cardio never
            // reaches this screen) that hasn't recorded anything yet, matching
            // WorkoutExercise.isSwitchEligible().
            val switchEligible = workoutExercise?.isSwitchEligible() == true && !isWarmup

            _uiState.value = StrengthExerciseUiState(
                exercise = exercise,
                sets = restoredSets,
                repRangeMin = repMin,
                repRangeMax = repMax,
                description = exercise.notes,
                isLoading = false,
                // Only meaningful when a draft was actually restored — a fresh load keeps
                // the default until the lifter touches something (see updateSet).
                allSetsFilled = draft != null &&
                    restoredSets.all { it.reps > 0 && (it.weight > 0 || weightOptional) },
                tonnagePr = tonnagePr,
                rmPr = rmPr,
                tonnagePrTargetReps = tonnagePrTargetReps,
                previousSessionDate = previousDate,
                previousHistoryDates = historyDates,
                switchEligible = switchEligible,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun updateReps(setIndex: Int, reps: Int) {
        crumb("set ${setIndex + 1} reps → $reps", key = "reps.$setIndex")
        // A manual reps choice is the new truth: drop the tonnage anchor so the next weight
        // change preserves *this* tonnage, not the one from before the edit.
        updateSet(setIndex) { applyRepsUpdate(it, reps) }
    }

    fun updateWeight(setIndex: Int, weight: Double) {
        crumb("set ${setIndex + 1} weight → $weight", key = "weight.$setIndex")
        // Only manual load has a meaningful "same tonnage" trade-off. For bodyweight the
        // picker is hidden; for assisted the picker is the assist offset, not the lifted
        // weight, so changing it must not rewrite reps.
        val manualLoad = _uiState.value.exercise?.loadMode == LoadMode.MANUAL
        updateSet(setIndex) { set -> applyWeightUpdate(set, weight, manualLoad) }
    }

    fun confirmReps(setIndex: Int) {
        crumb("set ${setIndex + 1} reps confirmed")
        updateSet(setIndex, ::confirmRepsUpdate)
    }

    fun confirmWeight(setIndex: Int) {
        crumb("set ${setIndex + 1} weight confirmed")
        // Confirming accepts the current reps×weight pair as the new baseline.
        updateSet(setIndex, ::confirmWeightUpdate)
    }

    fun updateDescription(text: String) {
        _uiState.value = _uiState.value.copy(description = text)
    }

    fun saveDescription(text: String) {
        viewModelScope.launch {
            val exercise = _uiState.value.exercise ?: return@launch
            exerciseRepository.save(exercise.copy(notes = text))
        }
    }

    /**
     * Builds the [ExerciseSet.Strength] list to persist. For a bodyweight exercise each set's
     * `weight` is materialized to `bwLoadPercent% of the lifter's body weight` (from the most
     * recent scale weigh-in on or before [sessionDate]); the raw percent and base weight are
     * kept on the set for audit. For an assisted exercise, [StrengthSetUi.weight] is the number
     * the lifter set on the assist machine — `weight` is materialized to
     * `bodyWeight - assistOffsetKg` (materializeAssistedWeight()), with the raw offset and base
     * weight kept on the set for audit, same pattern. Non-bodyweight/non-assisted sets pass
     * through unchanged.
     */
    private suspend fun buildStrengthSets(
        uiSets: List<StrengthSetUi>,
        sessionDate: String,
    ): List<ExerciseSet.Strength> {
        val exercise = _uiState.value.exercise
        return when (exercise?.loadMode) {
            LoadMode.BODYWEIGHT -> {
                val baseWeight = scaleHistoryRepository.getLatestWeightOnOrBefore(
                    java.time.LocalDate.parse(sessionDate)
                )
                val materialized = materializeBodyweightWeight(exercise.bwLoadPercent, baseWeight)
                uiSets.map {
                    ExerciseSet.Strength(
                        reps = it.reps,
                        weight = materialized,
                        isBodyweight = true,
                        bwLoadPercent = exercise.bwLoadPercent,
                        bwBaseWeightKg = baseWeight ?: 0.0,
                    )
                }
            }
            LoadMode.ASSISTED -> {
                val baseWeight = scaleHistoryRepository.getLatestWeightOnOrBefore(
                    java.time.LocalDate.parse(sessionDate)
                )
                uiSets.map {
                    ExerciseSet.Strength(
                        reps = it.reps,
                        weight = materializeAssistedWeight(baseWeight, it.weight),
                        isAssisted = true,
                        assistOffsetKg = it.weight,
                        bwBaseWeightKg = baseWeight ?: 0.0,
                    )
                }
            }
            else -> uiSets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
        }
    }

    /**
     * Called when the user taps "Complete Exercise". Persists set data on [clearScope] and
     * flips `completionSaved` once it lands, so the screen navigates back only after the write
     * is on disk (see [ExerciseSessionViewModel] / docs/CONVENTIONS.md#completionsaved-pattern).
     */
    fun completeExercise() {
        val sets = _uiState.value.sets
        val session = currentSession
        // "Touched" = the lifter tapped at least one reps/weight picker on this screen.
        // Tapping any single value is the signal that this exercise was actually performed
        // this session — the pre-filled numbers on the *other* (still-grey) sets are then
        // taken as done too and saved as-is. Tapping nothing means the exercise was not
        // performed and closes as completedEmpty (skipped styling; tonnage math skips it).
        val anyTouched = sets.anyTouched()
        crumb("complete $exerciseId (touched=$anyTouched, sets=${sets.size})")
        markCompletionAndSave {
            if (session != null) {
                val builtSets = buildStrengthSets(sets, session.date)
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = true,
                            completedEmpty = !anyTouched,
                            completedAt = java.time.LocalDateTime.now().toString(),
                            // Persist the shown numbers either way — grey pre-fills included.
                            // Even a completed-empty exercise keeps its pre-filled sets (not
                            // emptyList()) so the next session's prefill walk-back still finds
                            // this exercise's last real numbers instead of restarting at 0x0
                            // (the fixed-load warmup/daily regression — see CHANGELOG Phase 74).
                            sets = builtSets,
                        )
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
        }
    }

    // "Switch exercise": holds the new exerciseId once the swap is durably saved, so the
    // screen navigates to the same route with the new id only after the write is confirmed —
    // same race-avoidance reasoning as completionSaved (see its doc comment).
    private val _switchedExerciseId = MutableStateFlow<String?>(null)
    val switchedExerciseId: StateFlow<String?> = _switchedExerciseId

    /**
     * Applies the switch chosen from the filtered ExercisePicker. Reloads the session fresh
     * (the in-memory copy may be stale) and re-verifies eligibility via
     * [withExerciseSwitched] before saving. The recorded-sets guard is skipped exactly while
     * the current visit is untouched (the Complete button still reads "Segna come non
     * eseguito"), so a slot locked by an earlier accidental touch stays recoverable.
     */
    fun switchExercise(newExerciseId: String) {
        crumb("switch $exerciseId → $newExerciseId")
        viewModelScope.launch {
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val fresh = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
                ?: currentSession ?: return@launch
            val untouched = !_uiState.value.sets.anyTouched()
            val updated = fresh.withExerciseSwitched(
                exerciseId, newExercise, allowDiscardRecordedSets = untouched,
            )
            if (updated === fresh) return@launch // no longer eligible — ignore stale result
            workoutRepository.save(updated)
            currentSession = updated
            // This VM instance's own slot is gone now; nothing left to save from onCleared().
            markSwitched()
            _switchedExerciseId.value = newExerciseId
        }
    }

    override fun saveProgressOnExit() {
        // Back-navigation without completing: save current progress — but only real input.
        // Untouched sets keep whatever was on disk at entry (zeros for a fresh slot) via
        // mergeExitSets, so a zero-touch visit can't fabricate recorded data and lock the
        // "Switch exercise" slot (see its KDoc). Completion flags are preserved
        // (withExerciseSetsOnExit): a skipped slot the lifter re-entered stays skipped.
        val sets = _uiState.value.sets
        val session = currentSession
        crumb("exit without completing $exerciseId")
        val entrySets = session?.exercises?.find { it.exerciseId == exerciseId }?.sets
            ?.filterIsInstance<ExerciseSet.Strength>() ?: emptyList()
        clearScope.launch {
            if (session != null) {
                val builtSets = buildStrengthSets(sets, session.date)
                val exitSets = builtSets.mapIndexed { i, built ->
                    val touched = sets.getOrNull(i)?.let { it.repsTouched || it.weightTouched } ?: true
                    mergeExitSets(built, touched, completed = false, entrySets.getOrNull(i))
                }
                workoutRepository.save(session.withExerciseSetsOnExit(exerciseId, exitSets))
            }
            clearScope.cancel()
        }
    }

    private fun crumb(message: String, key: String? = null) =
        Breadcrumbs.add("strength", message, key?.let { "strength.$it" })

    private fun updateSet(index: Int, transform: (StrengthSetUi) -> StrengthSetUi) {
        val sets = _uiState.value.sets.toMutableList()
        if (index in sets.indices) {
            sets[index] = transform(sets[index])
            // Bodyweight exercises (Exercise.isBodyweight) have weight=0 by design — a
            // set with reps filled in but weight left at 0 is complete, not empty. Using
            // `&&` unconditionally would mean allSetsFilled could never become true for
            // any bodyweight exercise. Assisted exercises are the same: 0 on the picker
            // means "no assistance" (full body weight), a perfectly normal value, not an
            // untouched field.
            val loadMode = _uiState.value.exercise?.loadMode ?: LoadMode.MANUAL
            val allFilled = allSetsFilled(
                sets,
                weightOptional = loadMode == LoadMode.BODYWEIGHT || loadMode == LoadMode.ASSISTED,
            )
            _uiState.value = _uiState.value.copy(sets = sets, allSetsFilled = allFilled)
            // Mirror into the SavedStateHandle so a process kill loses at most the
            // in-flight keystroke, never the typed sets (single choke point: every
            // reps/weight/confirm path funnels through here).
            savedStateHandle[DRAFT_STRENGTH_SETS] = encodeStrengthDraft(sets)
        }
    }
}

/**
 * Explicit per-set touch/modify/anchor rules (ISM-20).
 *
 * A set carries two independent flag pairs: `repsModified`/`weightModified`
 * (purple display = differs from the pre-fill) vs `repsTouched`/`weightTouched`
 * (performed signal). Purple ≠ performed by design — a disk-loaded diff shows
 * purple at init yet still counts as untouched. `tonnageAnchor` keeps reps ×
 * weight constant across weight steps until a manual reps edit or a confirm
 * clears it. The handlers above only route through these; all rules are
 * unit-testable without the ViewModel.
 */

/** A manual reps choice is the new truth: flags on, anchor dropped. */
internal fun applyRepsUpdate(set: StrengthSetUi, reps: Int): StrengthSetUi =
    set.copy(
        reps = reps.coerceAtLeast(0),
        repsModified = true,
        repsTouched = true,
        tonnageAnchor = 0.0,
    )

/**
 * Weight-change rule: with touched reps the weight moves alone (anchor
 * cleared, no recalc); otherwise a manual load re-anchors (existing anchor,
 * else current reps × weight) and rewrites reps to the ceiling holding the
 * same tonnage — grey, never purple (`repsModified` preserved). Non-manual
 * loads (bodyweight/assisted pickers) never rewrite reps and clear the anchor.
 */
internal fun applyWeightUpdate(set: StrengthSetUi, weight: Double, manualLoad: Boolean): StrengthSetUi {
    val newWeight = weight.coerceAtLeast(0.0)
    // Se le reps sono già state toccate/confermate in questa visita, il peso
    // cambia da solo: non ricalcolare le reps. L'eguagliamento del tonnellaggio
    // vale solo finché le reps non sono state confermate.
    if (set.repsTouched) {
        return set.copy(
            weight = newWeight,
            weightModified = true,
            weightTouched = true,
            tonnageAnchor = 0.0,
        )
    }
    val anchor = if (manualLoad) {
        set.tonnageAnchor.takeIf { it > 0.0 }
            ?: (set.reps * set.weight).takeIf { it > 0.0 }
    } else {
        null
    }
    val adjustedReps = anchor?.let { repsForSameTonnage(it, newWeight) }
    return set.copy(
        weight = newWeight,
        reps = adjustedReps ?: set.reps,
        // Il ricalcolo automatico per tenere lo stesso tonnellaggio non deve
        // marcare le reps come modificate (restano grigie): solo una modifica
        // manuale delle reps o una conferma le promuove a viola.
        repsModified = set.repsModified,
        weightModified = true,
        weightTouched = true,
        tonnageAnchor = anchor ?: 0.0,
    )
}

/** Confirming accepts the current value and clears the anchor (see confirmWeightUpdate). */
internal fun confirmRepsUpdate(set: StrengthSetUi): StrengthSetUi =
    set.copy(repsModified = true, repsTouched = true, tonnageAnchor = 0.0)

/** Confirming accepts the current reps×weight pair as the new baseline. */
internal fun confirmWeightUpdate(set: StrengthSetUi): StrengthSetUi =
    set.copy(weightModified = true, weightTouched = true, tonnageAnchor = 0.0)

/**
 * "Touched" = the lifter tapped at least one reps/weight picker — the signal
 * the exercise was actually performed (grey pre-fills on untouched sets count
 * as done once any set is touched).
 */
internal fun List<StrengthSetUi>.anyTouched(): Boolean = any { it.repsTouched || it.weightTouched }

/**
 * A set is filled when reps are set and weight is set — except bodyweight /
 * assisted, where 0 is a real value (body weight by design / "no assistance"),
 * not an untouched field.
 */
internal fun allSetsFilled(sets: List<StrengthSetUi>, weightOptional: Boolean): Boolean =
    sets.all { it.reps > 0 && (it.weight > 0 || weightOptional) }

/**
 * Process-death drafts for strength sets (Sprint 9): `"reps|weight|repsTouched|weightTouched"`
 * rows, one per set. Previous-set references stay out — they reload fresh from the sidecar
 * every init. Size mismatch (routine edited between death and restore) ignores the draft
 * rather than misaligning sets. Pure helpers, unit-tested.
 */
internal const val DRAFT_STRENGTH_SETS = "draft_strength_sets"

internal data class StrengthSetDraft(
    val reps: Int,
    val weight: Double,
    val repsTouched: Boolean,
    val weightTouched: Boolean,
    /** Null nelle bozze legacy a 4 campi (pre stima viola-fix): ricalcolato come prima. */
    val repsModified: Boolean? = null,
    val weightModified: Boolean? = null,
)

internal fun encodeStrengthDraft(sets: List<StrengthSetUi>): ArrayList<String> =
    ArrayList(sets.map { "${it.reps}|${it.weight}|${it.repsTouched}|${it.weightTouched}|${it.repsModified}|${it.weightModified}" })

internal fun decodeStrengthDraft(raw: List<String>?): List<StrengthSetDraft>? {
    if (raw == null) return null
    return raw.map { row ->
        val p = row.split("|")
        if (p.size != 4 && p.size != 6) return null
        StrengthSetDraft(
            reps = p[0].toIntOrNull() ?: return null,
            weight = p[1].toDoubleOrNull() ?: return null,
            repsTouched = p[2].toBooleanStrictOrNull() ?: return null,
            weightTouched = p[3].toBooleanStrictOrNull() ?: return null,
            repsModified = if (p.size == 6) p[4].toBooleanStrictOrNull() ?: return null else null,
            weightModified = if (p.size == 6) p[5].toBooleanStrictOrNull() ?: return null else null,
        )
    }
}

internal fun applyStrengthDraft(
    base: List<StrengthSetUi>,
    draft: List<StrengthSetDraft>?,
): List<StrengthSetUi> {
    if (draft == null || draft.size != base.size) return base
    return base.mapIndexed { i, s ->
        val d = draft[i]
        s.copy(
            reps = d.reps,
            weight = d.weight,
            repsTouched = d.repsTouched,
            weightTouched = d.weightTouched,
            // I flag derivati viaggiano nella bozza (6 campi): un ricalcolo automatico
            // a tonnellaggio costante lascia repsModified=false anche se il valore
            // differisce dal previous, così resta grigio dopo un process-death restore.
            // Le bozze legacy a 4 campi ricalcolano come prima.
            repsModified = d.repsModified ?: (d.reps != s.previousReps),
            weightModified = d.weightModified ?: (d.weight != s.previousWeight),
        )
    }
}
