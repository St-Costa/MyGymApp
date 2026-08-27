package com.mygymapp.ui.screen.superset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.materializeBodyweightWeight
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
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
    val repsTouched: Boolean = false,
    val weightTouched: Boolean = false,
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
    // All-time best-tonnage set for each exercise (FORZA only), for the "PR" badge —
    // same definition as StrengthExerciseUiState.tonnagePr.
    val prReps1: Int = 0,
    val prWeight1: Double = 0.0,
    val prReps2: Int = 0,
    val prWeight2: Double = 0.0,
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): each side of a superset is an
    // independent slot — side 1 can be switched even if side 2 already has recorded sets, and
    // vice versa. excludeIds covers both sides plus every other exercise in the session.
    val switchEligible1: Boolean = false,
    val switchEligible2: Boolean = false,
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class SupersetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val routineRepository: RoutineRepository,
    private val workoutRepository: WorkoutRepository,
    private val scaleHistoryRepository: ScaleHistoryRepository,
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
    // The completeSuperset() save, tracked so onCleared() can join it before cancelling
    // clearScope — see StrengthExerciseViewModel for the full reasoning.
    private var completionJob: Job? = null

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
            suspend fun matchingSetsPerSession(exId: String, daily: Boolean, warmup: Boolean): List<List<ExerciseSet.Strength>> =
                workoutRepository.getSessionsForExercise(exId, 30)
                    .mapNotNull { prev ->
                        prev.exercises.firstOrNull { ex ->
                            ex.exerciseId == exId &&
                                ex.isDaily == daily &&
                                (ex.excludeFromTonnage && !ex.isDaily) == warmup
                        }
                    }
                    .map { it.sets.filterIsInstance<ExerciseSet.Strength>() }

            val matchingSessions1 = if (ex1.type == ExerciseType.FORZA) {
                matchingSetsPerSession(exerciseId1, isDaily1, isWarmup1)
            } else emptyList()
            val matchingSessions2 = if (ex2.type == ExerciseType.FORZA) {
                matchingSetsPerSession(exerciseId2, isDaily2, isWarmup2)
            } else emptyList()

            val prevStrengthSets1 = matchingSessions1.firstOrNull { s -> s.any { it.reps > 0 || it.weight > 0.0 } } ?: emptyList()
            val prevStrengthSets2 = matchingSessions2.firstOrNull { s -> s.any { it.reps > 0 || it.weight > 0.0 } } ?: emptyList()

            // All-time PR: the single set with the highest tonnage (reps * weight) ever
            // recorded for this exercise, across every session (not just the last 30 used
            // for "previous") and every non-excluded category — mirrors
            // StrengthExerciseViewModel's tonnagePr so the badge agrees whether the exercise
            // is opened standalone or as part of a superset. Warmup/daily sets don't count.
            suspend fun tonnagePr(exId: String, type: ExerciseType): ExerciseSet.Strength? {
                if (type != ExerciseType.FORZA) return null
                return workoutRepository.getSessionsForExercise(exId, Int.MAX_VALUE)
                    .asSequence()
                    .flatMap { prev -> prev.exercises.asSequence() }
                    .filter { it.exerciseId == exId && !it.excludeFromTonnage }
                    .flatMap { it.sets.asSequence().filterIsInstance<ExerciseSet.Strength>() }
                    .filter { it.reps > 0 && it.weight > 0.0 }
                    .maxByOrNull { it.reps * it.weight }
            }
            val prSet1 = tonnagePr(exerciseId1, ex1.type)
            val prSet2 = tonnagePr(exerciseId2, ex2.type)

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
                    // Guarded at the source: RoutineEditScreen's "Superset" link button never
                    // shows for a CARDIO exercise (a time-based block, not a set-based one —
                    // this screen only knows how to interleave FORZA/STRETCH sets).
                    ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
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
                    ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
                }
            }

            // Interleave sets: (ex1 set0, ex2 set0, ex1 set1, ex2 set1, ...)
            val interleaved = mutableListOf<SupersetSetUi>()
            val maxSets = maxOf(setCount1, setCount2)
            for (i in 0 until maxSets) {
                if (i < sets1.size) interleaved.add(sets1[i])
                if (i < sets2.size) interleaved.add(sets2[i])
            }

            // Switch is offered per side, only for a plain NORMAL slot with nothing recorded yet.
            val switchEligible1 = workoutEx1?.isSwitchEligible() == true && !isDaily1 && !isWarmup1
            val switchEligible2 = workoutEx2?.isSwitchEligible() == true && !isDaily2 && !isWarmup2

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
                prReps1 = prSet1?.reps ?: 0,
                prWeight1 = prSet1?.weight ?: 0.0,
                prReps2 = prSet2?.reps ?: 0,
                prWeight2 = prSet2?.weight ?: 0.0,
                switchEligible1 = switchEligible1,
                switchEligible2 = switchEligible2,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun updateReps(listIndex: Int, reps: Int) {
        updateSetAt(listIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true, repsTouched = true) }
    }

    fun updateWeight(listIndex: Int, weight: Double) {
        updateSetAt(listIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true, weightTouched = true) }
    }

    fun confirmReps(listIndex: Int) {
        updateSetAt(listIndex) { it.copy(repsModified = true, repsTouched = true) }
    }

    fun confirmWeight(listIndex: Int) {
        updateSetAt(listIndex) { it.copy(weightModified = true, weightTouched = true) }
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
        // Save on clearScope, not viewModelScope, so a process death between the tap and the
        // write completing can't lose it — see StrengthExerciseViewModel.completeExercise().
        completionJob = clearScope.launch {
            if (session != null) {
                session.buildUpdatedSession(sets, completed = true, respectTouch = true)
                    .let { workoutRepository.save(it) }
            }
            _completionSaved.value = true
        }
    }

    /**
     * Materialized `weight` for a bodyweight side of the superset: `bwLoadPercent% of the
     * lifter's body weight` from the latest weigh-in on or before [sessionDate], rounded to
     * 0.5 kg. Returns (0.0, 0) when the exercise is not bodyweight, and (0.0, percent) when it
     * is bodyweight but no weigh-in was available — mirrors StrengthExerciseViewModel.
     */
    private suspend fun bwMaterializedFor(exercise: Exercise?, sessionDate: String): Pair<Double, Double> {
        if (exercise?.isBodyweight != true) return 0.0 to 0.0
        val base = scaleHistoryRepository.getLatestWeightOnOrBefore(LocalDate.parse(sessionDate))
        return materializeBodyweightWeight(exercise.bwLoadPercent, base) to (base ?: 0.0)
    }

    // "Switch exercise": each side reports its own new exerciseId once durably saved, since
    // the two sides are independent slots and the screen needs to know which side changed to
    // re-navigate to the right Superset route (see StrengthExerciseViewModel for the pattern).
    private val _switchedExerciseId1 = MutableStateFlow<String?>(null)
    val switchedExerciseId1: StateFlow<String?> = _switchedExerciseId1
    private val _switchedExerciseId2 = MutableStateFlow<String?>(null)
    val switchedExerciseId2: StateFlow<String?> = _switchedExerciseId2

    fun switchExercise1(newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val updated = session.withExerciseSwitched(exerciseId1, newExercise)
            if (updated === session) return@launch
            workoutRepository.save(updated)
            supersetCompleted = true
            _switchedExerciseId1.value = newExerciseId
        }
    }

    fun switchExercise2(newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val updated = session.withExerciseSwitched(exerciseId2, newExercise)
            if (updated === session) return@launch
            workoutRepository.save(updated)
            supersetCompleted = true
            _switchedExerciseId2.value = newExerciseId
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        if (supersetCompleted) {
            // Wait for completeSuperset()'s clearScope save to finish before tearing the
            // scope down — see StrengthExerciseViewModel.onCleared().
            clearScope.launch {
                try {
                    completionJob?.join()
                } finally {
                    clearScope.cancel()
                }
            }
            return
        }
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                session.buildUpdatedSession(sets, completed = false, respectTouch = false)
                    .let { workoutRepository.save(it) }
            }
            clearScope.cancel()
        }
    }

    /**
     * @param respectTouch when true (only on explicit "Complete Superset"), a side of the
     * superset with no touched FORZA field and no toggled STRETCH set stays incomplete
     * (completed=false) — but its shown numbers, grey pre-fills included, are still persisted
     * so re-entry shows them again. Touching any one value on a side is the signal that side
     * was performed: it then closes as completed with every shown number saved as-is.
     */
    private suspend fun WorkoutSession.buildUpdatedSession(
        sets: List<SupersetSetUi>,
        completed: Boolean,
        respectTouch: Boolean,
    ): WorkoutSession {
        val sets1 = sets.filter { it.exerciseIndex == 0 }.sortedBy { it.setIndex }
        val sets2 = sets.filter { it.exerciseIndex == 1 }.sortedBy { it.setIndex }
        // Resolve the materialized bodyweight load once per side (see bwMaterializedFor).
        val (bwWeight1, bwBase1) = bwMaterializedFor(_uiState.value.exercise1, date)
        val (bwWeight2, bwBase2) = bwMaterializedFor(_uiState.value.exercise2, date)
        val bwPercent1 = _uiState.value.exercise1?.takeIf { it.isBodyweight }?.bwLoadPercent ?: 0
        val bwPercent2 = _uiState.value.exercise2?.takeIf { it.isBodyweight }?.bwLoadPercent ?: 0
        fun strengthSet(setUi: SupersetSetUi, bwPercent: Int, bwWeight: Double, bwBase: Double) =
            if (bwPercent > 0) ExerciseSet.Strength(
                reps = setUi.reps,
                weight = bwWeight,
                isBodyweight = true,
                bwLoadPercent = bwPercent,
                bwBaseWeightKg = bwBase,
            ) else ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
        fun anyTouched(sideSets: List<SupersetSetUi>) = sideSets.any {
            it.repsTouched || it.weightTouched || (it.exerciseType == ExerciseType.STRETCH && it.done)
        }
        // A side counts as "performed" only if the lifter touched at least one of its values.
        // An untouched side stays incomplete on an explicit Complete tap too (respectTouch=true)
        // — but its shown numbers are still saved (grey pre-fills included), same as a touched
        // side, so nothing is lost and re-entry shows them again.
        val side1Performed = !respectTouch || anyTouched(sets1)
        val side2Performed = !respectTouch || anyTouched(sets2)
        val updatedExercises = exercises.map { ex ->
            when (ex.exerciseId) {
                exerciseId1 -> ex.copy(
                    completed = completed && side1Performed,
                    completedEmpty = false,
                    sets = sets1.map { setUi ->
                        when (setUi.exerciseType) {
                            ExerciseType.FORZA -> strengthSet(setUi, bwPercent1, bwWeight1, bwBase1)
                            ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                            ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
                        }
                    },
                )
                exerciseId2 -> ex.copy(
                    completed = completed && side2Performed,
                    completedEmpty = false,
                    sets = sets2.map { setUi ->
                        when (setUi.exerciseType) {
                            ExerciseType.FORZA -> strengthSet(setUi, bwPercent2, bwWeight2, bwBase2)
                            ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                            ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
                        }
                    },
                )
                else -> ex
            }
        }
        return copy(exercises = updatedExercises)
    }
}
