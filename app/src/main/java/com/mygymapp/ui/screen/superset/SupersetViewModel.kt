package com.mygymapp.ui.screen.superset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SupersetSetUi(
    val exerciseIndex: Int,  // 0 = exercise1, 1 = exercise2
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val setIndex: Int,
    // FORZA fields
    val reps: Int = 0,
    val weight: Double = 0.0,
    val previousReps: Int = 0,
    val previousWeight: Double = 0.0,
    val repsModified: Boolean = false,
    val weightModified: Boolean = false,
    // STRETCH fields
    val timeSeconds: Int = 0,
    val done: Boolean = false,
)

data class SupersetUiState(
    val exercise1: Exercise? = null,
    val exercise2: Exercise? = null,
    val sets: List<SupersetSetUi> = emptyList(),
    val repRangeMin1: Int = 0,
    val repRangeMax1: Int = 0,
    val repRangeMin2: Int = 0,
    val repRangeMax2: Int = 0,
    val description1: String = "",
    val description2: String = "",
    val isLoading: Boolean = true,
    val isStopwatchRunning: Boolean = false,
    val elapsedSeconds: Int = 0,
)

@HiltViewModel
class SupersetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId1: String = savedStateHandle["exerciseId1"] ?: ""
    private val exerciseId2: String = savedStateHandle["exerciseId2"] ?: ""

    private val _uiState = MutableStateFlow(SupersetUiState())
    val uiState: StateFlow<SupersetUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var supersetCompleted = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val ex1 = exerciseRepository.getById(exerciseId1) ?: return@launch
            val ex2 = exerciseRepository.getById(exerciseId2) ?: return@launch

            val today = LocalDate.now()
            val sessions = workoutRepository.getSessionsInRange(today, today)
            val session = sessions.find { it.id == sessionId }
            currentSession = session

            val workoutEx1 = session?.exercises?.find { it.exerciseId == exerciseId1 }
            val workoutEx2 = session?.exercises?.find { it.exerciseId == exerciseId2 }
            val isDaily1 = workoutEx1?.isDaily ?: false
            val isDaily2 = workoutEx2?.isDaily ?: false
            val isWarmup1 = (workoutEx1?.excludeFromTonnage ?: false) && !isDaily1
            val isWarmup2 = (workoutEx2?.excludeFromTonnage ?: false) && !isDaily2

            // Previous FORZA sets for showing defaults. Compare like-with-like on exercise type
            // (daily / warmup / normal), and walk back to the most recent matching session that
            // actually has non-zero data so an empty 0-0 session doesn't blank out the preview.
            suspend fun previousStrengthSets(exId: String, daily: Boolean, warmup: Boolean): List<ExerciseSet.Strength> =
                workoutRepository.getSessionsForExercise(exId, 30)
                    .asSequence()
                    .mapNotNull { prev ->
                        prev.exercises.firstOrNull { ex ->
                            ex.exerciseId == exId &&
                                ex.isDaily == daily &&
                                (ex.excludeFromTonnage && !ex.isDaily) == warmup
                        }
                    }
                    .map { it.sets.filterIsInstance<ExerciseSet.Strength>() }
                    .firstOrNull { s -> s.any { it.reps > 0 || it.weight > 0.0 } } ?: emptyList()

            val prevStrengthSets1 = if (ex1.type == ExerciseType.FORZA) {
                previousStrengthSets(exerciseId1, isDaily1, isWarmup1)
            } else emptyList()

            val prevStrengthSets2 = if (ex2.type == ExerciseType.FORZA) {
                previousStrengthSets(exerciseId2, isDaily2, isWarmup2)
            } else emptyList()

            // Fallback for sets beyond what the previous session recorded.
            val lastMeaningful1 = prevStrengthSets1.lastOrNull { it.reps > 0 || it.weight > 0.0 }
            val lastMeaningful2 = prevStrengthSets2.lastOrNull { it.reps > 0 || it.weight > 0.0 }

            // Rep ranges from the owning routine. Daily exercises live in the fixed-daily routine.
            var repMin1 = 0; var repMax1 = 0
            var repMin2 = 0; var repMax2 = 0
            val sessionRoutineId = session?.routineId ?: ""
            val routineId1 =
                if (isDaily1) com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID else sessionRoutineId
            val routineId2 =
                if (isDaily2) com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID else sessionRoutineId
            if (routineId1.isNotBlank()) {
                val re1 = routineRepository.getById(routineId1)?.exercises?.find { it.exerciseId == exerciseId1 }
                repMin1 = re1?.repRangeMin ?: 0; repMax1 = re1?.repRangeMax ?: 0
            }
            if (routineId2.isNotBlank()) {
                val re2 = routineRepository.getById(routineId2)?.exercises?.find { it.exerciseId == exerciseId2 }
                repMin2 = re2?.repRangeMin ?: 0; repMax2 = re2?.repRangeMax ?: 0
            }

            val setCount1 = workoutEx1?.sets?.size ?: 3
            val setCount2 = workoutEx2?.sets?.size ?: 3

            val sets1 = (0 until setCount1).map { i ->
                val currentSet = workoutEx1?.sets?.getOrNull(i)
                when (ex1.type) {
                    ExerciseType.FORZA -> {
                        val cs = currentSet as? ExerciseSet.Strength
                        val ps = prevStrengthSets1.getOrNull(i) ?: lastMeaningful1
                        val hasCurrentData = cs != null && (cs.reps != 0 || cs.weight != 0.0)
                        val displayReps = if (hasCurrentData) cs!!.reps else ps?.reps ?: 0
                        val displayWeight = if (hasCurrentData) cs!!.weight else ps?.weight ?: 0.0
                        SupersetSetUi(
                            exerciseIndex = 0,
                            exerciseName = ex1.name,
                            exerciseType = ex1.type,
                            setIndex = i,
                            reps = displayReps,
                            weight = displayWeight,
                            previousReps = ps?.reps ?: 0,
                            previousWeight = ps?.weight ?: 0.0,
                            repsModified = hasCurrentData && displayReps != (ps?.reps ?: 0),
                            weightModified = hasCurrentData && displayWeight != (ps?.weight ?: 0.0),
                        )
                    }
                    ExerciseType.STRETCH -> {
                        val cs = currentSet as? ExerciseSet.Stretch
                        SupersetSetUi(
                            exerciseIndex = 0,
                            exerciseName = ex1.name,
                            exerciseType = ex1.type,
                            setIndex = i,
                            timeSeconds = cs?.timeSeconds ?: 60,
                            done = cs?.done ?: false,
                        )
                    }
                }
            }

            val sets2 = (0 until setCount2).map { i ->
                val currentSet = workoutEx2?.sets?.getOrNull(i)
                when (ex2.type) {
                    ExerciseType.FORZA -> {
                        val cs = currentSet as? ExerciseSet.Strength
                        val ps = prevStrengthSets2.getOrNull(i) ?: lastMeaningful2
                        val hasCurrentData = cs != null && (cs.reps != 0 || cs.weight != 0.0)
                        val displayReps = if (hasCurrentData) cs!!.reps else ps?.reps ?: 0
                        val displayWeight = if (hasCurrentData) cs!!.weight else ps?.weight ?: 0.0
                        SupersetSetUi(
                            exerciseIndex = 1,
                            exerciseName = ex2.name,
                            exerciseType = ex2.type,
                            setIndex = i,
                            reps = displayReps,
                            weight = displayWeight,
                            previousReps = ps?.reps ?: 0,
                            previousWeight = ps?.weight ?: 0.0,
                            repsModified = hasCurrentData && displayReps != (ps?.reps ?: 0),
                            weightModified = hasCurrentData && displayWeight != (ps?.weight ?: 0.0),
                        )
                    }
                    ExerciseType.STRETCH -> {
                        val cs = currentSet as? ExerciseSet.Stretch
                        SupersetSetUi(
                            exerciseIndex = 1,
                            exerciseName = ex2.name,
                            exerciseType = ex2.type,
                            setIndex = i,
                            timeSeconds = cs?.timeSeconds ?: 60,
                            done = cs?.done ?: false,
                        )
                    }
                }
            }

            // Interleave sets: (ex1 set0, ex2 set0, ex1 set1, ex2 set1, ...)
            val interleaved = mutableListOf<SupersetSetUi>()
            val maxSets = maxOf(setCount1, setCount2)
            for (i in 0 until maxSets) {
                if (i < sets1.size) interleaved.add(sets1[i])
                if (i < sets2.size) interleaved.add(sets2[i])
            }

            _uiState.value = SupersetUiState(
                exercise1 = ex1,
                exercise2 = ex2,
                sets = interleaved,
                repRangeMin1 = repMin1,
                repRangeMax1 = repMax1,
                repRangeMin2 = repMin2,
                repRangeMax2 = repMax2,
                description1 = ex1.notes,
                description2 = ex2.notes,
                isLoading = false,
            )
        }
    }

    fun updateReps(listIndex: Int, reps: Int) {
        updateSetAt(listIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true) }
    }

    fun updateWeight(listIndex: Int, weight: Double) {
        updateSetAt(listIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true) }
    }

    fun confirmReps(listIndex: Int) {
        updateSetAt(listIndex) { it.copy(repsModified = true) }
    }

    fun confirmWeight(listIndex: Int) {
        updateSetAt(listIndex) { it.copy(weightModified = true) }
    }

    fun toggleStopwatch() {
        val wasRunning = _uiState.value.isStopwatchRunning
        if (wasRunning) {
            timerJob?.cancel()
            timerJob = null
            _uiState.value = _uiState.value.copy(isStopwatchRunning = false)
        } else {
            timerJob?.cancel()
            _uiState.value = _uiState.value.copy(isStopwatchRunning = true, elapsedSeconds = 0)
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    _uiState.value = _uiState.value.copy(
                        elapsedSeconds = _uiState.value.elapsedSeconds + 1
                    )
                }
            }
        }
    }

    fun toggleSetDone(listIndex: Int) {
        updateSetAt(listIndex) { it.copy(done = !it.done) }
    }

    private fun updateSetAt(listIndex: Int, transform: (SupersetSetUi) -> SupersetSetUi) {
        val sets = _uiState.value.sets.toMutableList()
        if (listIndex in sets.indices) {
            sets[listIndex] = transform(sets[listIndex])
            _uiState.value = _uiState.value.copy(sets = sets)
        }
    }

    fun updateDescription1(text: String) {
        _uiState.value = _uiState.value.copy(description1 = text)
    }

    fun saveDescription1(text: String) {
        viewModelScope.launch {
            val exercise = _uiState.value.exercise1 ?: return@launch
            exerciseRepository.save(exercise.copy(notes = text))
        }
    }

    fun updateDescription2(text: String) {
        _uiState.value = _uiState.value.copy(description2 = text)
    }

    fun saveDescription2(text: String) {
        viewModelScope.launch {
            val exercise = _uiState.value.exercise2 ?: return@launch
            exerciseRepository.save(exercise.copy(notes = text))
        }
    }

    private val _completionSaved = MutableStateFlow(false)
    val completionSaved: StateFlow<Boolean> = _completionSaved

    /**
     * Called when the user taps "Complete Superset".
     * Saves set data to disk and then emits [completionSaved] = true so the screen
     * can navigate back only after the write is guaranteed to be on disk.
     */
    fun completeSuperset() {
        supersetCompleted = true
        val sets = _uiState.value.sets
        val session = currentSession
        viewModelScope.launch {
            if (session != null) saveThisPair(session, sets, completed = true)
            _completionSaved.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        if (supersetCompleted) {
            clearScope.cancel()
            return
        }
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) saveThisPair(session, sets, completed = false)
            clearScope.cancel()
        }
    }

    // Reload the session from disk before rebuilding it, so we don't overwrite
    // fields (Polar metrics, other exercises' updates, notes) added to the disk
    // copy after this VM was initialized. buildUpdatedSession only rewrites the
    // two exercises in this superset; everything else on `fresh` is preserved.
    private suspend fun saveThisPair(
        original: WorkoutSession,
        sets: List<SupersetSetUi>,
        completed: Boolean,
    ) {
        val today = java.time.LocalDate.parse(original.date)
        val fresh = workoutRepository.getSession(original.id, today) ?: original
        workoutRepository.save(fresh.buildUpdatedSession(sets, completed))
    }

    private fun WorkoutSession.buildUpdatedSession(
        sets: List<SupersetSetUi>,
        completed: Boolean,
    ): WorkoutSession {
        val sets1 = sets.filter { it.exerciseIndex == 0 }.sortedBy { it.setIndex }
        val sets2 = sets.filter { it.exerciseIndex == 1 }.sortedBy { it.setIndex }
        val updatedExercises = exercises.map { ex ->
            when (ex.exerciseId) {
                exerciseId1 -> ex.copy(
                    completed = completed,
                    sets = sets1.map { setUi ->
                        when (setUi.exerciseType) {
                            ExerciseType.FORZA -> ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
                            ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                        }
                    },
                )
                exerciseId2 -> ex.copy(
                    completed = completed,
                    sets = sets2.map { setUi ->
                        when (setUi.exerciseType) {
                            ExerciseType.FORZA -> ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
                            ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                        }
                    },
                )
                else -> ex
            }
        }
        return copy(exercises = updatedExercises)
    }
}
