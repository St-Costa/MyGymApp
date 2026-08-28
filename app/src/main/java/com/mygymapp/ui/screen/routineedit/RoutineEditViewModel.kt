package com.mygymapp.ui.screen.routineedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.DataChangedSignal
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID
import com.mygymapp.data.model.Routine
import com.mygymapp.data.model.RoutineExercise
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.ui.util.MAX_SUPERSET_SIZE
import com.mygymapp.ui.util.groupSupersets
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
    /** True for the reserved "Fixed daily exercise" container (no warmup line, locked name/day). */
    val isFixedDaily: Boolean = false,
    /** Number of leading exercises above the warmup line (positional divider). */
    val warmupCount: Int = 0,
)

val DAYS_OF_WEEK = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

// ---------------------------------------------------------------------------
// Superset segment helpers (also used by the Screen)
// ---------------------------------------------------------------------------

sealed class ExerciseSegment {
    data class Single(val index: Int) : ExerciseSegment()
    data class Superset(val indices: List<Int>) : ExerciseSegment()

    fun indices(): List<Int> = when (this) {
        is Single -> listOf(index)
        is Superset -> indices
    }
}

fun buildExerciseSegments(exercises: List<RoutineExerciseUi>): List<ExerciseSegment> =
    groupSupersets(
        items = exercises,
        isPairedWithNext = { it.supersetWithNext },
        single = { i -> ExerciseSegment.Single(i) },
        group = { idxs -> ExerciseSegment.Superset(idxs) },
    )

/**
 * Whether turning on the superset link at [index] (which merges the segment holding [index]
 * with the one starting at `index + 1`) keeps the chain within [MAX_SUPERSET_SIZE]. Pure so
 * the cap rule is unit-testable without the ViewModel. Returns false if [index] is the last
 * element (nothing to link forward) or already linked.
 */
fun canEnableSupersetLink(segments: List<ExerciseSegment>, index: Int, count: Int): Boolean {
    if (index < 0 || index + 1 >= count) return false
    val here = segments.firstOrNull { index in it.indices() }?.indices()?.size ?: 1
    val next = segments.firstOrNull { (index + 1) in it.indices() }?.indices()?.size ?: 1
    return here + next <= MAX_SUPERSET_SIZE
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
                        isFixedDaily = routine.id == FIXED_DAILY_ROUTINE_ID,
                        // Warmup exercises are persisted as the leading prefix of the list.
                        warmupCount = routine.exercises.takeWhile { it.isWarmup }.size,
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
                // CARDIO has no per-exercise default duration — it's set per routine, in
                // RoutineEditScreen's own "Durata cardio" picker (RoutineExerciseUi's default
                // of 60s, meant for STRETCH's "per set" seconds, applies here too until edited).
            )
            _uiState.value = _uiState.value.copy(
                exercises = _uiState.value.exercises + newItem,
            )
        }
    }

    fun removeExercise(index: Int) {
        val list = _uiState.value.exercises.toMutableList()
        if (index in list.indices) {
            // If the previous exercise has supersetWithNext=true, this exercise was a member of
            // that chain. Removing a middle member (A+[B]+C) just re-links A to C via A's own
            // still-true flag, which is fine. Removing the last member (A+B+[C]) would leave B's
            // flag dangling past the end of its segment — clear it so no chain runs off the end.
            if (index > 0 && list[index - 1].supersetWithNext &&
                (index == list.lastIndex || !list[index].supersetWithNext)
            ) {
                list[index - 1] = list[index - 1].copy(supersetWithNext = false)
            }
            list.removeAt(index)
            // Removing an exercise above the line shifts the line up by one.
            val warmupCount = _uiState.value.warmupCount
            val newWarmup = if (index < warmupCount) warmupCount - 1 else warmupCount
            _uiState.value = _uiState.value.copy(exercises = list, warmupCount = newWarmup)
        }
    }

    fun toggleSuperset(index: Int) {
        // Never link the last warmup exercise with the first normal exercise across the line.
        if (index + 1 == _uiState.value.warmupCount) return
        val exercises = _uiState.value.exercises
        val turningOn = index in exercises.indices && !exercises[index].supersetWithNext
        if (turningOn && !canEnableSupersetLink(buildExerciseSegments(exercises), index, exercises.size)) {
            // Merged chain would exceed MAX_SUPERSET_SIZE (e.g. an existing A+B+C can't absorb a D).
            return
        }
        updateExercise(index) { it.copy(supersetWithNext = !it.supersetWithNext) }
    }

    /** Pushes the first normal segment up into the warmup section (moves the line down). */
    fun moveWarmupLineDown() {
        val state = _uiState.value
        val segments = buildExerciseSegments(state.exercises)
        var acc = 0
        for (seg in segments) {
            val size = seg.indices().size
            if (acc == state.warmupCount) {
                _uiState.value = state.copy(warmupCount = acc + size)
                return
            }
            acc += size
        }
    }

    /** Pulls the last warmup segment back into the normal section (moves the line up). */
    fun moveWarmupLineUp() {
        val state = _uiState.value
        val segments = buildExerciseSegments(state.exercises)
        var acc = 0
        for (seg in segments) {
            val size = seg.indices().size
            if (acc + size == state.warmupCount) {
                _uiState.value = state.copy(warmupCount = acc)
                return
            }
            acc += size
        }
    }

    /** Snaps [warmupCount] to the nearest segment boundary so it never splits a superset pair. */
    private fun snapWarmupToBoundary(exercises: List<RoutineExerciseUi>, warmupCount: Int): Int {
        val segments = buildExerciseSegments(exercises)
        var acc = 0
        for (seg in segments) {
            val size = seg.indices().size
            if (warmupCount <= acc) return acc
            if (warmupCount < acc + size) return acc // inside a pair → snap before it
            acc += size
        }
        return acc
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

        // The warmup line is positional: dragging a segment across it changes which exercises
        // are warmup. Re-snap to a segment boundary so a pair is never split by the line.
        val snapped = snapWarmupToBoundary(mutable, _uiState.value.warmupCount)
        _uiState.value = _uiState.value.copy(exercises = mutable, warmupCount = snapped)
    }

    fun updateExerciseSets(index: Int, sets: Int) {
        updateExercise(index) { it.copy(sets = sets.coerceAtLeast(1)) }
    }

    fun updateExerciseRepMin(index: Int, value: Int) {
        updateExercise(index) {
            val newMin = value.coerceAtLeast(1)
            // Keep max >= min: if user raises min above max, pull max up with it.
            val newMax = if (newMin > it.repRangeMax) newMin else it.repRangeMax
            it.copy(repRangeMin = newMin, repRangeMax = newMax)
        }
    }

    fun updateExerciseRepMax(index: Int, value: Int) {
        updateExercise(index) {
            val newMax = value.coerceAtLeast(1)
            // Symmetric: if user drops max below min, pull min down with it.
            val newMin = if (newMax < it.repRangeMin) newMax else it.repRangeMin
            it.copy(repRangeMin = newMin, repRangeMax = newMax)
        }
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
        exercises = state.exercises.mapIndexed { index, ex ->
            RoutineExercise(
                exerciseId = ex.exerciseId,
                sets = ex.sets,
                repRangeMin = ex.repRangeMin,
                repRangeMax = ex.repRangeMax,
                timePerSetSeconds = ex.timePerSetSeconds,
                supersetWithNext = ex.supersetWithNext,
                // The container has no warmup concept; otherwise the leading prefix is warmup.
                isWarmup = !state.isFixedDaily && index < state.warmupCount,
            )
        },
    )
}
