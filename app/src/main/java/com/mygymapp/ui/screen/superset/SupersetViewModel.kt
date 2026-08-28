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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SupersetSetUi(
    val exerciseIndex: Int,  // position of the exercise within the chain (0..2)
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

/** One member of the superset chain (2–3 of these). */
data class SupersetMemberUi(
    val exercise: Exercise,
    val repRangeMin: Int = 0,
    val repRangeMax: Int = 0,
    val description: String = "",
    // All-time best-tonnage set (FORZA only), for the "PR" badge — same definition as
    // StrengthExerciseUiState.tonnagePr, matched per slot context.
    val prReps: Int = 0,
    val prWeight: Double = 0.0,
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): each member is an independent
    // slot — one can be switched even if another already has recorded sets.
    val switchEligible: Boolean = false,
)

data class SupersetUiState(
    val members: List<SupersetMemberUi> = emptyList(),
    val sets: List<SupersetSetUi> = emptyList(),
    val isLoading: Boolean = true,
    val isStopwatchRunning: Boolean = false,
    val elapsedSeconds: Int = 0,
    // Union of every exerciseId in the session, so the "switch" picker never offers a
    // duplicate of any occupied slot.
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
    // The 2–3 chain members in execution order, comma-separated in the nav arg.
    private val exerciseIds: List<String> =
        (savedStateHandle.get<String>("exerciseIds") ?: "")
            .split(",")
            .filter { it.isNotBlank() }

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
            val exercises = exerciseIds.map { id ->
                exerciseRepository.getById(id) ?: return@launch
            }
            if (exercises.isEmpty()) return@launch

            val today = LocalDate.now()
            val sessions = workoutRepository.getSessionsInRange(today, today)
            val session = sessions.find { it.id == sessionId }
            currentSession = session

            val sessionRoutineId = session?.routineId ?: ""

            // Build each member independently and in parallel: every member does its own
            // getExerciseStats (a small sidecar read, but a full per-exercise history scan the
            // first time it's built) + a routine lookup. Sequentially that's N× the latency —
            // the reason a 3-member superset felt slow on first open. async/awaitAll overlaps
            // the I/O; the coroutine dispatcher is Dispatchers.Main here but every repository
            // call hops to Dispatchers.IO internally, so the awaits actually run concurrently.
            data class MemberBuild(val member: SupersetMemberUi, val sets: List<SupersetSetUi>)

            val builds = exercises.mapIndexed { memberIndex, ex ->
                async {
                val exId = ex.id
                val workoutEx = session?.exercises?.find { it.exerciseId == exId }
                val isDaily = workoutEx?.isDaily ?: false
                val isWarmup = (workoutEx?.excludeFromTonnage ?: false) && !isDaily
                // Which of the three mutually-exclusive slot categories this exercise is in
                // right now — both the "previous" pre-fill and the all-time PR compare only
                // against prior sessions in the same category.
                val slotContext = workoutEx?.slotContext ?: com.mygymapp.data.model.SlotContext.NORMAL

                // Previous FORZA sets + all-time PR come from the per-exercise stats sidecar
                // (history/_stats/{id}.yaml) — one small read per member instead of parsing
                // every session file that contains it (×N members made superset load slow).
                // The sidecar already matches on slot context and already stores the most
                // recent session with real (non-zero) data as "previous".
                val ctxStats = workoutRepository.getExerciseStats(exId).forContext(slotContext)
                val prevStrengthSets: List<ExerciseSet.Strength> =
                    if (ex.type == ExerciseType.FORZA)
                        ctxStats?.previousSets.orEmpty().map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
                    else emptyList()
                val prSet: ExerciseSet.Strength? =
                    if (ex.type == ExerciseType.FORZA)
                        ctxStats?.pr?.let { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
                    else null

                // Fallback for sets beyond what the previous session recorded.
                val lastMeaningful = prevStrengthSets.lastOrNull { it.reps > 0 || it.weight > 0.0 }

                // Rep ranges from the owning routine. Daily exercises live in the fixed-daily routine.
                var repMin = 0
                var repMax = 0
                val routineId =
                    if (isDaily) com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID else sessionRoutineId
                if (routineId.isNotBlank()) {
                    val re = routineRepository.getById(routineId)?.exercises?.find { it.exerciseId == exId }
                    repMin = re?.repRangeMin ?: 0
                    repMax = re?.repRangeMax ?: 0
                }

                val setCount = workoutEx?.sets?.size ?: 3
                val memberSets = (0 until setCount).map { i ->
                    val currentSet = workoutEx?.sets?.getOrNull(i)
                    when (ex.type) {
                        ExerciseType.FORZA -> {
                            val cs = currentSet as? ExerciseSet.Strength
                            val ps = prevStrengthSets.getOrNull(i) ?: lastMeaningful
                            val hasCurrentData = cs != null && (cs.reps != 0 || cs.weight != 0.0)
                            val displayReps = if (hasCurrentData) cs!!.reps else ps?.reps ?: 0
                            val displayWeight = if (hasCurrentData) cs!!.weight else ps?.weight ?: 0.0
                            SupersetSetUi(
                                exerciseIndex = memberIndex,
                                exerciseName = ex.name,
                                exerciseType = ex.type,
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
                                exerciseIndex = memberIndex,
                                exerciseName = ex.name,
                                exerciseType = ex.type,
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
                val switchEligible = workoutEx?.isSwitchEligible() == true && !isDaily && !isWarmup
                MemberBuild(
                    member = SupersetMemberUi(
                        exercise = ex,
                        repRangeMin = repMin,
                        repRangeMax = repMax,
                        description = ex.notes,
                        prReps = prSet?.reps ?: 0,
                        prWeight = prSet?.weight ?: 0.0,
                        switchEligible = switchEligible,
                    ),
                    sets = memberSets,
                )
                }
            }.awaitAll()

            val members = builds.map { it.member }
            val perMemberSets = builds.map { it.sets }

            // Interleave sets round-by-round: (m0 set0, m1 set0, m2 set0, m0 set1, ...).
            val maxSets = perMemberSets.maxOf { it.size }
            val interleaved = mutableListOf<SupersetSetUi>()
            for (round in 0 until maxSets) {
                perMemberSets.forEach { sets -> sets.getOrNull(round)?.let(interleaved::add) }
            }

            _uiState.value = SupersetUiState(
                members = members,
                sets = interleaved,
                isLoading = false,
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

    fun updateDescriptionAt(memberIndex: Int, text: String) {
        val members = _uiState.value.members.toMutableList()
        if (memberIndex in members.indices) {
            members[memberIndex] = members[memberIndex].copy(description = text)
            _uiState.value = _uiState.value.copy(members = members)
        }
    }

    fun saveDescriptionAt(memberIndex: Int, text: String) {
        viewModelScope.launch {
            val exercise = _uiState.value.members.getOrNull(memberIndex)?.exercise ?: return@launch
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
     * Materialized `weight` for a bodyweight member: `bwLoadPercent% of the lifter's body
     * weight` from the latest weigh-in on or before [sessionDate], rounded to 0.5 kg. Returns
     * (0.0, 0) when the exercise is not bodyweight, and (0.0, percent) when it is bodyweight
     * but no weigh-in was available — mirrors StrengthExerciseViewModel.
     */
    private suspend fun bwMaterializedFor(exercise: Exercise?, sessionDate: String): Pair<Double, Double> {
        if (exercise?.isBodyweight != true) return 0.0 to 0.0
        val base = scaleHistoryRepository.getLatestWeightOnOrBefore(LocalDate.parse(sessionDate))
        return materializeBodyweightWeight(exercise.bwLoadPercent, base) to (base ?: 0.0)
    }

    // "Switch exercise": the switched member reports its own position + new exerciseId once
    // durably saved, since members are independent slots and the screen needs to know which
    // one changed to re-navigate to the right Superset route (see StrengthExerciseViewModel).
    private val _switchedMember = MutableStateFlow<Pair<Int, String>?>(null)
    val switchedMember: StateFlow<Pair<Int, String>?> = _switchedMember

    fun switchExerciseAt(memberIndex: Int, newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val oldExerciseId = exerciseIds.getOrNull(memberIndex) ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val updated = session.withExerciseSwitched(oldExerciseId, newExercise)
            if (updated === session) return@launch
            workoutRepository.save(updated)
            supersetCompleted = true
            _switchedMember.value = memberIndex to newExerciseId
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
     * @param respectTouch when true (only on explicit "Complete Superset"), a member with no
     * touched FORZA field and no toggled STRETCH set stays incomplete (completed=false) — but
     * its shown numbers, grey pre-fills included, are still persisted so re-entry shows them
     * again. Touching any one value on a member is the signal it was performed: it then closes
     * as completed with every shown number saved as-is.
     */
    private suspend fun WorkoutSession.buildUpdatedSession(
        sets: List<SupersetSetUi>,
        completed: Boolean,
        respectTouch: Boolean,
    ): WorkoutSession {
        val members = _uiState.value.members
        // Resolve the materialized bodyweight load once per member (see bwMaterializedFor).
        data class Bw(val percent: Int, val weight: Double, val base: Double)
        val bwByIndex = members.map { m ->
            val (w, base) = bwMaterializedFor(m.exercise, date)
            Bw(m.exercise.takeIf { it.isBodyweight }?.bwLoadPercent ?: 0, w, base)
        }
        fun strengthSet(setUi: SupersetSetUi, bw: Bw) =
            if (bw.percent > 0) ExerciseSet.Strength(
                reps = setUi.reps,
                weight = bw.weight,
                isBodyweight = true,
                bwLoadPercent = bw.percent,
                bwBaseWeightKg = bw.base,
            ) else ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
        fun anyTouched(memberSets: List<SupersetSetUi>) = memberSets.any {
            it.repsTouched || it.weightTouched || (it.exerciseType == ExerciseType.STRETCH && it.done)
        }

        // A member counts as "performed" only if the lifter touched at least one of its
        // values. An untouched member stays incomplete on an explicit Complete tap too
        // (respectTouch=true) — but its shown numbers are still saved (grey pre-fills
        // included), same as a touched member, so nothing is lost and re-entry shows them.
        val updatedExercises = exercises.map { ex ->
            val mi = exerciseIds.indexOf(ex.exerciseId)
            if (mi < 0) return@map ex
            val memberSets = sets.filter { it.exerciseIndex == mi }.sortedBy { it.setIndex }
            val performed = !respectTouch || anyTouched(memberSets)
            val bw = bwByIndex.getOrElse(mi) { Bw(0, 0.0, 0.0) }
            ex.copy(
                completed = completed && performed,
                completedEmpty = false,
                sets = memberSets.map { setUi ->
                    when (setUi.exerciseType) {
                        ExerciseType.FORZA -> strengthSet(setUi, bw)
                        ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                        ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
                    }
                },
            )
        }
        return copy(exercises = updatedExercises)
    }
}
