package com.mygymapp.ui.screen.strengthexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Best single set ever recorded for this exercise, by tonnage (reps * weight). */
data class TonnagePr(val reps: Int, val weight: Double)

data class StrengthExerciseUiState(
    val exercise: Exercise? = null,
    val sets: List<StrengthSetUi> = emptyList(),
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val description: String = "",
    val isLoading: Boolean = true,
    val allSetsFilled: Boolean = false,
    val tonnagePr: TonnagePr? = null,
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
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(StrengthExerciseUiState())
    val uiState: StateFlow<StrengthExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var exerciseCompleted = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            // Find current session to get set count, rep range, daily-status, and in-progress values
            val sessions = workoutRepository.getSessionsInRange(
                java.time.LocalDate.now(), java.time.LocalDate.now()
            )
            val session = sessions.find { it.id == sessionId }
            currentSession = session

            val workoutExercise = session?.exercises?.find { it.exerciseId == exerciseId }
            // Whether this exercise is being performed as a fixed-daily exercise in this session.
            val isDaily = workoutExercise?.isDaily ?: false
            // The exercise "type" in this session, used to compare like-with-like below.
            // Three mutually exclusive types: warmup, fixed-daily, normal.
            val currentExcludeFromTonnage = workoutExercise?.excludeFromTonnage ?: false
            val isWarmup = currentExcludeFromTonnage && !isDaily

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

            // Find previous workout data for this exercise (for showing grey "previous" values).
            // Progress must compare like-with-like: only against prior sessions where this exercise
            // had the same type (daily / warmup / normal). Walk back through history and use the
            // most recent matching session that actually has non-zero set data, so an empty 0-0
            // session doesn't blank out the preview.
            val matchingSetsPerSession = workoutRepository.getSessionsForExercise(exerciseId, 30)
                .mapNotNull { prev ->
                    prev.exercises.firstOrNull { ex ->
                        ex.exerciseId == exerciseId &&
                            ex.isDaily == isDaily &&
                            (ex.excludeFromTonnage && !ex.isDaily) == isWarmup
                    }
                }
                .map { it.sets.filterIsInstance<ExerciseSet.Strength>() }

            val previousSets = matchingSetsPerSession.firstOrNull { strengthSets ->
                strengthSets.any { it.reps > 0 || it.weight > 0.0 }
            } ?: emptyList()

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

            // All-time PR: the single set with the highest tonnage (reps * weight) ever recorded
            // for this exercise, across every session (not just the last 30 used for "previous").
            // Warmup sets don't count toward a real PR.
            val tonnagePr = workoutRepository.getSessionsForExercise(exerciseId, Int.MAX_VALUE)
                .asSequence()
                .flatMap { prev -> prev.exercises.asSequence() }
                .filter { it.exerciseId == exerciseId && !it.excludeFromTonnage }
                .flatMap { it.sets.asSequence().filterIsInstance<ExerciseSet.Strength>() }
                .filter { it.reps > 0 && it.weight > 0.0 }
                .maxByOrNull { it.reps * it.weight }
                ?.let { TonnagePr(reps = it.reps, weight = it.weight) }

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

    private val _completionSaved = MutableStateFlow(false)
    val completionSaved: StateFlow<Boolean> = _completionSaved

    /**
     * Called when the user taps "Complete Exercise".
     * Saves set data to disk and then emits [completionSaved] = true so the screen
     * can navigate back only after the write is guaranteed to be on disk.
     * This prevents the race condition where ActiveRoutineViewModel reloads the session
     * before onCleared() has finished writing.
     */
    fun completeExercise() {
        exerciseCompleted = true
        val sets = _uiState.value.sets
        val session = currentSession
        val isBodyweight = _uiState.value.exercise?.isBodyweight ?: false
        val anyTouched = sets.any { it.repsTouched || it.weightTouched }
        viewModelScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        // If the lifter never touched any pre-filled value, there's no evidence
                        // the exercise was actually performed — don't silently re-record last
                        // session's numbers as new work. Still closes as completed (the lifter
                        // did tap Complete) but flagged empty so the list can warn about it.
                        if (anyTouched) {
                            ex.copy(
                                completed = true,
                                completedEmpty = false,
                                sets = sets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight, isBodyweight = isBodyweight) },
                            )
                        } else {
                            ex.copy(completed = true, completedEmpty = true, sets = emptyList())
                        }
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            _completionSaved.value = true
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
            exerciseCompleted = true
            _switchedExerciseId.value = newExerciseId
        }
    }

    override fun onCleared() {
        if (exerciseCompleted) {
            // Already saved via completeExercise() — nothing to do
            clearScope.cancel()
            return
        }
        // Back-navigation without completing: save current progress as incomplete
        val sets = _uiState.value.sets
        val session = currentSession
        val isBodyweight = _uiState.value.exercise?.isBodyweight ?: false
        clearScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = false,
                            sets = sets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight, isBodyweight = isBodyweight) },
                        )
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
