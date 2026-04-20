package com.mygymapp.ui.screen.routineedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoutineExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val sets: Int = 3,
    val repRangeMin: Int = 8,
    val repRangeMax: Int = 12,
    val timePerSetSeconds: Int = 60,
    val supersetWithNext: Boolean = false,
)

data class RoutineEditUiState(
    val id: String = "",
    val name: String = "",
    val day: String = "",
    val notes: String = "",
    val exercises: List<RoutineExerciseUi> = emptyList(),
    val isNew: Boolean = true,
    val deleted: Boolean = false,
)

val DAYS_OF_WEEK = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

// ---------------------------------------------------------------------------
// Superset segment helpers (also used by the Screen)
// ---------------------------------------------------------------------------

sealed class ExerciseSegment {
    data class Single(val index: Int) : ExerciseSegment()
    data class SupersetPair(val index1: Int, val index2: Int) : ExerciseSegment()

    fun indices(): List<Int> = when (this) {
        is Single -> listOf(index)
        is SupersetPair -> listOf(index1, index2)
    }
}

fun buildExerciseSegments(exercises: List<RoutineExerciseUi>): List<ExerciseSegment> {
    val segments = mutableListOf<ExerciseSegment>()
    var i = 0
    while (i < exercises.size) {
        if (exercises[i].supersetWithNext && i + 1 < exercises.size) {
            segments.add(ExerciseSegment.SupersetPair(i, i + 1))
            i += 2
        } else {
            segments.add(ExerciseSegment.Single(i))
            i++
        }
    }
    return segments
}

@HiltViewModel
class RoutineEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val dataChangedSignal: DataChangedSignal,
) : ViewModel() {

    private val routineId: String? = savedStateHandle.get<String>("id")?.takeIf { it.isNotBlank() }

    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(RoutineEditUiState())
    val uiState: StateFlow<RoutineEditUiState> = _uiState

    init {
        viewModelScope.launch {
            if (routineId != null) {
                val routine = routineRepository.getById(routineId)
                if (routine != null) {
                    val exerciseUis = routine.exercises.mapNotNull { re ->
                        val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                        RoutineExerciseUi(
                            exerciseId = re.exerciseId,
                            exerciseName = exercise.name,
                            exerciseType = exercise.type,
                            sets = re.sets,
                            repRangeMin = re.repRangeMin,
                            repRangeMax = re.repRangeMax,
                            timePerSetSeconds = re.timePerSetSeconds,
                            supersetWithNext = re.supersetWithNext,
                        )
                    }
                    _uiState.value = RoutineEditUiState(
                        id = routine.id,
                        name = routine.name,
                        day = routine.day,
                        notes = routine.notes,
                        exercises = exerciseUis,
                        isNew = false,
                    )
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun onDayChange(day: String) {
        _uiState.value = _uiState.value.copy(day = day)
    }

    fun onNotesChange(value: String) {
        _uiState.value = _uiState.value.copy(notes = value)
    }

    fun addExercise(exerciseId: String) {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch
            val already = _uiState.value.exercises.any { it.exerciseId == exerciseId }
            if (already) return@launch
            val newItem = RoutineExerciseUi(
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                exerciseType = exercise.type,
                repRangeMin = exercise.defaultRepRangeMin,
                repRangeMax = exercise.defaultRepRangeMax,
            )
            _uiState.value = _uiState.value.copy(
                exercises = _uiState.value.exercises + newItem,
            )
        }
    }

    fun removeExercise(index: Int) {
        val list = _uiState.value.exercises.toMutableList()
        if (index in list.indices) {
            // If the previous exercise has supersetWithNext=true (this is its second element),
            // clear the previous exercise's supersetWithNext flag to avoid a dangling link.
            if (index > 0 && list[index - 1].supersetWithNext) {
                list[index - 1] = list[index - 1].copy(supersetWithNext = false)
            }
            list.removeAt(index)
            _uiState.value = _uiState.value.copy(exercises = list)
        }
    }

    fun toggleSuperset(index: Int) {
        updateExercise(index) { it.copy(supersetWithNext = !it.supersetWithNext) }
    }

    fun moveSegment(fromSegIdx: Int, toSegIdx: Int) {
        val exercises = _uiState.value.exercises
        val segments = buildExerciseSegments(exercises)
        if (fromSegIdx !in segments.indices || toSegIdx !in segments.indices || fromSegIdx == toSegIdx) return

        val fromSeg = segments[fromSegIdx]
        val sourceExercises = fromSeg.indices().map { exercises[it] }

        val mutable = exercises.toMutableList()
        // Remove source exercises (higher index first to avoid index shifting)
        fromSeg.indices().sortedDescending().forEach { mutable.removeAt(it) }

        // Rebuild segments on the reduced list to find the correct insertion point
        val newSegments = buildExerciseSegments(mutable)
        val effectiveToSegIdx = if (toSegIdx > fromSegIdx) toSegIdx - 1 else toSegIdx

        val insertAt: Int = if (toSegIdx > fromSegIdx) {
            // Moving down: insert after the target segment's last exercise
            val targetSeg = newSegments.getOrNull(effectiveToSegIdx) ?: newSegments.last()
            targetSeg.indices().last() + 1
        } else {
            // Moving up: insert before the target segment's first exercise
            val targetSeg = newSegments.getOrNull(effectiveToSegIdx) ?: return
            targetSeg.indices().first()
        }

        sourceExercises.forEachIndexed { i, ex ->
            mutable.add((insertAt + i).coerceIn(0, mutable.size), ex)
        }

        _uiState.value = _uiState.value.copy(exercises = mutable)
    }

    fun updateExerciseSets(index: Int, sets: Int) {
        updateExercise(index) { it.copy(sets = sets.coerceAtLeast(1)) }
    }

    fun updateExerciseRepMin(index: Int, value: Int) {
        updateExercise(index) { it.copy(repRangeMin = value.coerceAtLeast(1)) }
    }

    fun updateExerciseRepMax(index: Int, value: Int) {
        updateExercise(index) { it.copy(repRangeMax = value.coerceAtLeast(1)) }
    }

    fun updateExerciseTime(index: Int, seconds: Int) {
        updateExercise(index) { it.copy(timePerSetSeconds = seconds.coerceAtLeast(1)) }
    }

    private fun updateExercise(index: Int, transform: (RoutineExerciseUi) -> RoutineExerciseUi) {
        val list = _uiState.value.exercises.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _uiState.value = _uiState.value.copy(exercises = list)
        }
    }

    fun deleteRoutine() {
        val id = routineId ?: return
        viewModelScope.launch {
            routineRepository.delete(id)
            dataChangedSignal.notifyRoutinesChanged()
            _uiState.value = _uiState.value.copy(deleted = true)
        }
    }

    // Called from the Screen's back button / BackHandler before popBackStack().
    private var savedExplicitly = false
    suspend fun saveNow() {
        val state = _uiState.value
        if (state.name.isBlank() || state.deleted) return
        val routine = buildRoutine(state)
        routineRepository.save(routine)
        dataChangedSignal.notifyRoutinesChanged()
        savedExplicitly = true
    }

    override fun onCleared() {
        super.onCleared()
        if (savedExplicitly) return
        val state = _uiState.value
        if (state.name.isBlank() || state.deleted) return
        clearScope.launch {
            try {
                val routine = buildRoutine(state)
                routineRepository.save(routine)
                dataChangedSignal.notifyRoutinesChanged()
            } finally {
                clearScope.cancel()
            }
        }
    }

    private fun buildRoutine(state: RoutineEditUiState): Routine = Routine(
        id = state.id,
        name = state.name.trim(),
        day = state.day,
        notes = state.notes.trim(),
        exercises = state.exercises.map { ex ->
            RoutineExercise(
                exerciseId = ex.exerciseId,
                sets = ex.sets,
                repRangeMin = ex.repRangeMin,
                repRangeMax = ex.repRangeMax,
                timePerSetSeconds = ex.timePerSetSeconds,
                supersetWithNext = ex.supersetWithNext,
            )
        },
    )
}
