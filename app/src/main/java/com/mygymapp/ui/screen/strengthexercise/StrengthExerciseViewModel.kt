package com.mygymapp.ui.screen.strengthexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.materializeBodyweightWeight
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.ui.screen.exercise.ExerciseSessionViewModel
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
)

/**
 * Best single set ever recorded for this exercise, by tonnage (reps * weight).
 * [bwBaseWeightKg] is the body weight at the time it was logged for a bodyweight exercise
 * (0.0 otherwise / legacy) — bodyweight screens show `reps x bwBaseWeightKg` instead of
 * `reps x weight` (which for bodyweight is only bwLoadPercent% of the body weight).
 */
data class TonnagePr(val reps: Int, val weight: Double, val bwBaseWeightKg: Double = 0.0)

/**
 * The single set ever recorded for this exercise with the highest estimated 1RM (Epley:
 * `weight * (1 + reps/30)`). Same fields as [TonnagePr] — the badge shows this set's
 * `reps x weight`, not the computed 1RM — but it is a *different* set: a heavy low-rep set
 * can hold this record without holding the tonnage one. [bwBaseWeightKg] is the body weight
 * at logging time for a bodyweight exercise (0.0 otherwise / legacy); the badge is hidden
 * for a bodyweight exercise when it's unknown, since the stored `weight` is materialized load.
 */
data class RmPr(val reps: Int, val weight: Double, val bwBaseWeightKg: Double = 0.0)

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
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): true only for a plain NORMAL
    // slot with zero recorded sets so far — warmup/daily/completed/already-switched slots
    // never show the button. excludeIds is every exerciseId already occupying a slot in this
    // session, passed to the filtered picker so it can never offer a duplicate.
    val switchEligible: Boolean = false,
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class StrengthExerciseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
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
            val session = workoutRepository.getSession(sessionId, java.time.LocalDate.now())
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
            // every session file that contains the exercise. Both are already matched
            // like-with-like on slot context (daily / warmup / normal) inside the sidecar, and
            // "previous" is already the most recent session with real (non-zero) set data.
            val ctxStats = workoutRepository.getExerciseStats(exerciseId).forContext(slotContext)
            val previousSets = ctxStats?.previousSets.orEmpty()
                .map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }

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

            // All-time PRs (highest reps*weight set, and highest estimated-1RM set, in this
            // slot context) — also from the sidecar, same as "previous" above. The two can be
            // different sets; both badges show that set's reps x weight, RM on top.
            val tonnagePr = ctxStats?.pr?.let {
                TonnagePr(reps = it.reps, weight = it.weight, bwBaseWeightKg = it.bwBaseWeightKg)
            }
            val rmPr = ctxStats?.rmPr?.let {
                RmPr(reps = it.reps, weight = it.weight, bwBaseWeightKg = it.bwBaseWeightKg)
            }

            // Switch is offered only for a plain NORMAL slot (not warmup/daily/cardio — cardio
            // never reaches this screen) that hasn't recorded anything yet, matching
            // WorkoutExercise.isSwitchEligible().
            val switchEligible = workoutExercise?.isSwitchEligible() == true && !isDaily && !isWarmup

            _uiState.value = StrengthExerciseUiState(
                exercise = exercise,
                sets = sets,
                repRangeMin = repMin,
                repRangeMax = repMax,
                description = exercise.notes,
                isLoading = false,
                tonnagePr = tonnagePr,
                rmPr = rmPr,
                switchEligible = switchEligible,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun updateReps(setIndex: Int, reps: Int) {
        updateSet(setIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true, repsTouched = true) }
    }

    fun updateWeight(setIndex: Int, weight: Double) {
        updateSet(setIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true, weightTouched = true) }
    }

    fun confirmReps(setIndex: Int) {
        updateSet(setIndex) { it.copy(repsModified = true, repsTouched = true) }
    }

    fun confirmWeight(setIndex: Int) {
        updateSet(setIndex) { it.copy(weightModified = true, weightTouched = true) }
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
     * kept on the set for audit. Non-bodyweight sets pass through unchanged.
     */
    private suspend fun buildStrengthSets(
        uiSets: List<StrengthSetUi>,
        sessionDate: String,
    ): List<ExerciseSet.Strength> {
        val exercise = _uiState.value.exercise
        if (exercise?.isBodyweight != true) {
            return uiSets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
        }
        val baseWeight = scaleHistoryRepository.getLatestWeightOnOrBefore(
            java.time.LocalDate.parse(sessionDate)
        )
        val materialized = materializeBodyweightWeight(exercise.bwLoadPercent, baseWeight)
        return uiSets.map {
            ExerciseSet.Strength(
                reps = it.reps,
                weight = materialized,
                isBodyweight = true,
                bwLoadPercent = exercise.bwLoadPercent,
                bwBaseWeightKg = baseWeight ?: 0.0,
            )
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
        val anyTouched = sets.any { it.repsTouched || it.weightTouched }
        markCompletionAndSave {
            if (session != null) {
                val builtSets = buildStrengthSets(sets, session.date)
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = true,
                            completedEmpty = !anyTouched,
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
     * Applies the switch chosen from the filtered ExercisePicker. Re-verifies eligibility
     * against the freshly-reloaded session (not just the UI flag) via
     * [withExerciseSwitched] before saving.
     */
    fun switchExercise(newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val updated = session.withExerciseSwitched(exerciseId, newExercise)
            if (updated === session) return@launch // no longer eligible — ignore stale result
            workoutRepository.save(updated)
            // This VM instance's own slot is gone now; nothing left to save from onCleared().
            markSwitched()
            _switchedExerciseId.value = newExerciseId
        }
    }

    override fun saveProgressOnExit() {
        // Back-navigation without completing: save current progress as incomplete
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                val builtSets = buildStrengthSets(sets, session.date)
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(completed = false, sets = builtSets)
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            clearScope.cancel()
        }
    }

    private fun updateSet(index: Int, transform: (StrengthSetUi) -> StrengthSetUi) {
        val sets = _uiState.value.sets.toMutableList()
        if (index in sets.indices) {
            sets[index] = transform(sets[index])
            // Bodyweight exercises (Exercise.isBodyweight) have weight=0 by design — a
            // set with reps filled in but weight left at 0 is complete, not empty. Using
            // `&&` unconditionally would mean allSetsFilled could never become true for
            // any bodyweight exercise.
            val isBodyweight = _uiState.value.exercise?.isBodyweight ?: false
            val allFilled = sets.all { it.reps > 0 && (it.weight > 0 || isBodyweight) }
            _uiState.value = _uiState.value.copy(sets = sets, allSetsFilled = allFilled)
        }
    }
}
