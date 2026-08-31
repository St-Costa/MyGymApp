package com.mygymapp.ui.screen.superset

import androidx.lifecycle.SavedStateHandle
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
import com.mygymapp.ui.screen.exercise.ExerciseSessionViewModel
import com.mygymapp.ui.util.MAX_SUPERSET_SIZE
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SupersetSetUi(
    val exerciseIndex: Int,  // position of the exercise within the chain (0 until MAX_SUPERSET_SIZE)
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
    // Body weight at the time the PR set was logged (bodyweight members only, 0.0 otherwise /
    // legacy) — shown as `reps x prBwBaseWeightKg` instead of `reps x prWeight`.
    val prBwBaseWeightKg: Double = 0.0,
    // The set with the highest estimated 1RM (Epley) ever logged for this exercise (FORZA
    // only), shown as a second "RM" badge above the tonnage one as `reps x weight` — the set,
    // not the computed 1RM. Can be a different set than the tonnage PR. reps == 0 ⇒ badge
    // hidden (no eligible set). For a bodyweight member the badge is also hidden unless
    // [prE1rmBwBaseWeightKg] is known.
    val prE1rmReps: Int = 0,
    val prE1rmWeight: Double = 0.0,
    val prE1rmBwBaseWeightKg: Double = 0.0,
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
) : ExerciseSessionViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    // The 2–3 chain members in execution order, comma-separated in the nav arg. Capped at
    // MAX_SUPERSET_SIZE defensively (a hand-edited route could carry more).
    private val exerciseIds: List<String> =
        (savedStateHandle.get<String>("exerciseIds") ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .take(MAX_SUPERSET_SIZE)

    private val _uiState = MutableStateFlow(SupersetUiState())
    val uiState: StateFlow<SupersetUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val exercises = exerciseIds.map { id ->
                exerciseRepository.getById(id) ?: return@launch
            }
            if (exercises.isEmpty()) return@launch

            val today = LocalDate.now()
            // The active session is always dated today; fetch it straight by id.
            val session = workoutRepository.getSession(sessionId, today)
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
                        ctxStats?.pr?.let {
                            ExerciseSet.Strength(
                                reps = it.reps,
                                weight = it.weight,
                                bwBaseWeightKg = it.bwBaseWeightKg,
                            )
                        }
                    else null
                val rmPrSet: ExerciseSet.Strength? =
                    if (ex.type == ExerciseType.FORZA)
                        ctxStats?.rmPr?.let {
                            ExerciseSet.Strength(
                                reps = it.reps,
                                weight = it.weight,
                                bwBaseWeightKg = it.bwBaseWeightKg,
                            )
                        }
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
                        prBwBaseWeightKg = prSet?.bwBaseWeightKg ?: 0.0,
                        prE1rmReps = rmPrSet?.reps ?: 0,
                        prE1rmWeight = rmPrSet?.weight ?: 0.0,
                        prE1rmBwBaseWeightKg = rmPrSet?.bwBaseWeightKg ?: 0.0,
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

    /**
     * A lightweight in-VM rest timer, deliberately NOT the foreground [StopwatchService] (which
     * the CARDIO screen uses): a superset rest countdown is only meaningful while this screen is
     * on top, and it resets when the lifter leaves. No notification / background survival needed
     * — it stops with the ViewModel. Same choice in StretchExerciseViewModel.
     */
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

    /**
     * Called when the user taps "Complete Superset". Persists on [clearScope], flips
     * `completionSaved` once on disk — see [ExerciseSessionViewModel].
     */
    fun completeSuperset() {
        val sets = _uiState.value.sets
        val session = currentSession
        markCompletionAndSave {
            if (session != null) {
                session.buildUpdatedSession(sets, completed = true)
                    .let { workoutRepository.save(it) }
            }
        }
    }

    // The lifter's body weight doesn't change within a session, so the weigh-in lookup that
    // feeds bodyweight materialization is done once (lazily, first time a save needs it) and
    // cached — not once per member per save/autosave. Null means "not resolved yet"; the inner
    // value can itself be null when there is no weigh-in on or before the session date.
    private var cachedBaseWeight: Result<Double?>? = null

    private suspend fun sessionBaseWeight(sessionDate: String): Double? {
        cachedBaseWeight?.let { return it.getOrNull() }
        val resolved = runCatching {
            scaleHistoryRepository.getLatestWeightOnOrBefore(LocalDate.parse(sessionDate))
        }
        cachedBaseWeight = resolved
        return resolved.getOrNull()
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
            markSwitched()
            _switchedMember.value = memberIndex to newExerciseId
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    override fun saveProgressOnExit() {
        val sets = _uiState.value.sets
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                session.buildUpdatedSession(sets, completed = false)
                    .let { workoutRepository.save(it) }
            }
            clearScope.cancel()
        }
    }

    /**
     * @param completed true only on an explicit "Complete Superset" tap: every member closes
     * out. A member the lifter never touched (no touched FORZA field, no toggled STRETCH set)
     * closes as completedEmpty=true — the active-routine row then shows the neutral "skipped"
     * styling (grey border + X) for that member and tonnage math skips it. Its shown numbers,
     * grey pre-fills included, are still persisted so the next session's prefill finds real
     * numbers. Touching any one value clears the flag: the member closes as real work.
     * When false (back-out / autosave) nothing closes — completed/completedEmpty stay false.
     */
    private suspend fun WorkoutSession.buildUpdatedSession(
        sets: List<SupersetSetUi>,
        completed: Boolean,
    ): WorkoutSession {
        val members = _uiState.value.members
        // One weigh-in lookup for the whole chain; per-member differences are only the percent.
        val base = if (members.any { it.exercise.isBodyweight }) sessionBaseWeight(date) else null
        data class Bw(val percent: Int, val weight: Double, val base: Double)
        val bwByIndex = members.map { m ->
            if (m.exercise.isBodyweight) Bw(
                percent = m.exercise.bwLoadPercent,
                weight = materializeBodyweightWeight(m.exercise.bwLoadPercent, base),
                base = base ?: 0.0,
            ) else Bw(0, 0.0, 0.0)
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

        // On an explicit "Complete Superset" (completed=true) every member closes out; a member
        // the lifter never touched closes as completedEmpty (skipped) rather than staying open.
        // Its shown numbers are still saved (grey pre-fills included) so nothing is lost.
        // On back-out / autosave (completed=false) nothing closes.
        val updatedExercises = exercises.map { ex ->
            val mi = exerciseIds.indexOf(ex.exerciseId)
            if (mi < 0) return@map ex
            val memberSets = sets.filter { it.exerciseIndex == mi }.sortedBy { it.setIndex }
            val performed = anyTouched(memberSets)
            val bw = bwByIndex.getOrElse(mi) { Bw(0, 0.0, 0.0) }
            ex.copy(
                completed = completed,
                completedEmpty = completed && !performed,
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
