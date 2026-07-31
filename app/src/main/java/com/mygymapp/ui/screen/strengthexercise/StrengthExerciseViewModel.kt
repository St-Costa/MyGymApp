package com.mygymapp.ui.screen.strengthexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.WorkoutSession
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
)

data class StrengthExerciseUiState(
    val exercise: Exercise? = null,
    val sets: List<StrengthSetUi> = emptyList(),
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val description: String = "",
    val isLoading: Boolean = true,
    val allSetsFilled: Boolean = false,
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
            val setCount = workoutExercise?.sets?.size ?: 3
            // Whether this exercise is being performed as a fixed-daily exercise in this session.
            val isDaily = workoutExercise?.isDaily ?: false
            // The exercise "type" in this session, used to compare like-with-like below.
            // Three mutually exclusive types: warmup, fixed-daily, normal.
            val currentExcludeFromTonnage = workoutExercise?.excludeFromTonnage ?: false
            val isWarmup = currentExcludeFromTonnage && !isDaily

            // Find previous workout data for this exercise (for showing grey "previous" values).
            // Progress must compare like-with-like: only against prior sessions where this exercise
            // had the same type (daily / warmup / normal). Walk back through history and use the
            // most recent matching session that actually has non-zero set data, so an empty 0-0
            // session doesn't blank out the preview.
            val previousSets = workoutRepository.getSessionsForExercise(exerciseId, 30)
                .asSequence()
                .mapNotNull { prev ->
                    prev.exercises.firstOrNull { ex ->
                        ex.exerciseId == exerciseId &&
                            ex.isDaily == isDaily &&
                            (ex.excludeFromTonnage && !ex.isDaily) == isWarmup
                    }
                }
                .map { it.sets.filterIsInstance<ExerciseSet.Strength>() }
                .firstOrNull { strengthSets ->
                    strengthSets.any { it.reps > 0 || it.weight > 0.0 }
                } ?: emptyList()

            // Get rep range from the owning routine. Daily exercises live in the fixed-daily
            // routine, not the session's routine, so look them up there.
            var repMin = 0
            var repMax = 0
            val rangeRoutineId =
                if (isDaily) com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID else session?.routineId ?: ""
            if (rangeRoutineId.isNotBlank()) {
                val routine = routineRepository.getById(rangeRoutineId)
                val routineExercise = routine?.exercises?.find { it.exerciseId == exerciseId }
                repMin = routineExercise?.repRangeMin ?: 0
                repMax = routineExercise?.repRangeMax ?: 0
            }

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

            _uiState.value = StrengthExerciseUiState(
                exercise = exercise,
                sets = sets,
                repRangeMin = repMin,
                repRangeMax = repMax,
                description = exercise.notes,
                isLoading = false,
            )
        }
    }

    fun updateReps(setIndex: Int, reps: Int) {
        updateSet(setIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true) }
    }

    fun updateWeight(setIndex: Int, weight: Double) {
        updateSet(setIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true) }
    }

    fun confirmReps(setIndex: Int) {
        updateSet(setIndex) { it.copy(repsModified = true) }
    }

    fun confirmWeight(setIndex: Int) {
        updateSet(setIndex) { it.copy(weightModified = true) }
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
        viewModelScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = true,
                            sets = sets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) },
                        )
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            _completionSaved.value = true
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
        clearScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(
                            completed = false,
                            sets = sets.map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) },
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
            val allFilled = sets.all { it.reps > 0 && it.weight > 0 }
            _uiState.value = _uiState.value.copy(sets = sets, allSetsFilled = allFilled)
        }
    }
}
