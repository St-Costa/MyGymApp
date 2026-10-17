package com.mygymapp.ui.screen.superset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.LoadMode
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.materializeAssistedWeight
import com.mygymapp.data.model.materializeBodyweightWeight
import com.mygymapp.data.model.withExerciseSwitched
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.ScaleHistoryRepository
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.Breadcrumbs
import com.mygymapp.ui.screen.exercise.ExerciseSessionViewModel
import com.mygymapp.ui.screen.exercise.mergeExitSets
import com.mygymapp.ui.screen.stretchexercise.StretchCountdown
import com.mygymapp.ui.screen.stretchexercise.tick
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
    /** Day key of the session the tonnage-PR set comes from, "" when unknown. Saved but intentionally not shown. */
    val prSessionDate: String = "",
    /** Day key of the session the e1RM-PR set comes from, "" when unknown. Saved but intentionally not shown. */
    val prE1rmSessionDate: String = "",
    /** Day key of the session the grey pre-fill comes from, "" when none. Saved but intentionally not shown. */
    val previousSessionDate: String = "",
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): each member is an independent
    // slot — one can be switched even if another already has recorded sets.
    val switchEligible: Boolean = false,
)

data class SupersetUiState(
    val members: List<SupersetMemberUi> = emptyList(),
    val sets: List<SupersetSetUi> = emptyList(),
    val isLoading: Boolean = true,
    val isStopwatchRunning: Boolean = false,
    // Countdown-loop timer shared by the chain, shown while at least one member is STRETCH:
    // counts down from [countdownDuration] (first stretch set's duration, see
    // [supersetCountdownDuration]) and restarts automatically at zero — sets stay manual
    // (toggled via checkbox). Display only: buzzes + notification live in StopwatchService,
    // which the screen starts with the same duration.
    val countdownDuration: Int = 60,
    val remainingSeconds: Int = 60,
    val loopCount: Int = 1,
    // Union of every exerciseId in the session, so the "switch" picker never offers a
    // duplicate of any occupied slot.
    val excludeIds: Set<String> = emptySet(),
)

@HiltViewModel
class SupersetViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
                // The sidecar already matches on slot context AND on weighting approach
                // (bodyweight vs manual load, schema v4) and already stores the most recent
                // such session with real (non-zero) data as "previous".
                val ctxStats = workoutRepository.getExerciseStats(exId).forContext(slotContext)
                val prevStrengthSets: List<ExerciseSet.Strength> =
                    if (ex.type == ExerciseType.FORZA)
                        ctxStats?.previousSetsFor(ex.loadMode).orEmpty()
                            .map {
                                // The picker's "weight" always mirrors what the lifter dials
                                // in: manual load, or the assist-machine number for an assisted
                                // exercise (never the materialized net weight) — see
                                // StrengthExerciseViewModel / PreviousSet.assistOffsetKg.
                                ExerciseSet.Strength(
                                    reps = it.reps,
                                    weight = if (ex.loadMode == com.mygymapp.data.model.LoadMode.ASSISTED)
                                        it.assistOffsetKg else it.weight,
                                )
                            }
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
                            val current = if (hasCurrentData) cs else null
                            val displayReps = current?.reps ?: ps?.reps ?: 0
                            val displayWeight = current?.weight ?: ps?.weight ?: 0.0
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
                val switchEligible = workoutEx?.isSwitchEligible() == true && !isWarmup
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
                        prSessionDate = ctxStats?.prSessionDate.orEmpty(),
                        prE1rmSessionDate = ctxStats?.rmPrSessionDate.orEmpty(),
                        previousSessionDate = ctxStats?.previousSessionDateFor(ex.loadMode).orEmpty(),
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

            // Process-death restore: unsaved keystrokes survive in the SavedStateHandle —
            // overlay them over the just-loaded sets (previous/PR references always come
            // from the fresh load above, never the draft). Timers are deliberately NOT
            // restored (see StretchExerciseViewModel).
            val restoredSets = applySupersetDraft(
                interleaved,
                decodeSupersetDraft(savedStateHandle.get<ArrayList<String>>(DRAFT_SUPERSET_SETS)),
            )

            val countdownDuration = supersetCountdownDuration(restoredSets)
            _uiState.value = SupersetUiState(
                members = members,
                sets = restoredSets,
                isLoading = false,
                countdownDuration = countdownDuration,
                remainingSeconds = countdownDuration,
                excludeIds = session?.exercises?.map { it.exerciseId }?.toSet() ?: emptySet(),
            )
        }
    }

    fun updateReps(listIndex: Int, reps: Int) {
        crumb("row $listIndex reps → $reps", key = "reps.$listIndex")
        updateSetAt(listIndex) { it.copy(reps = reps.coerceAtLeast(0), repsModified = true, repsTouched = true) }
    }

    fun updateWeight(listIndex: Int, weight: Double) {
        crumb("row $listIndex weight → $weight", key = "weight.$listIndex")
        updateSetAt(listIndex) { it.copy(weight = weight.coerceAtLeast(0.0), weightModified = true, weightTouched = true) }
    }

    fun confirmReps(listIndex: Int) {
        crumb("row $listIndex reps confirmed")
        updateSetAt(listIndex) { it.copy(repsModified = true, repsTouched = true) }
    }

    fun confirmWeight(listIndex: Int) {
        crumb("row $listIndex weight confirmed")
        updateSetAt(listIndex) { it.copy(weightModified = true, weightTouched = true) }
    }

    /**
     * Countdown-loop timer, mirroring StretchExerciseViewModel: counts down from the chain's
     * stretch set duration ([supersetCountdownDuration]) and wraps at zero. The screen starts
     * /stops the foreground [StopwatchService] in lockstep with the same duration extra, so
     * the buzzes + notification match the on-screen loop.
     */
    fun toggleStopwatch() {
        val wasRunning = _uiState.value.isStopwatchRunning
        if (wasRunning) {
            timerJob?.cancel()
            timerJob = null
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = false,
                remainingSeconds = _uiState.value.countdownDuration,
                loopCount = 1,
            )
        } else {
            timerJob?.cancel()
            val countdown = StretchCountdown.start(_uiState.value.countdownDuration)
            _uiState.value = _uiState.value.copy(
                isStopwatchRunning = true,
                countdownDuration = countdown.remainingSeconds,
                remainingSeconds = countdown.remainingSeconds,
                loopCount = countdown.loopCount,
            )
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    val current = _uiState.value
                    val (next, _) = StretchCountdown(
                        remainingSeconds = current.remainingSeconds,
                        loopCount = current.loopCount,
                    ).tick(current.countdownDuration)
                    _uiState.value = current.copy(
                        remainingSeconds = next.remainingSeconds,
                        loopCount = next.loopCount,
                    )
                }
            }
        }
    }

    fun toggleSetDone(listIndex: Int) {
        crumb("row $listIndex done toggled")
        updateSetAt(listIndex) { it.copy(done = !it.done) }
    }

    private fun crumb(message: String, key: String? = null) =
        Breadcrumbs.add("superset", message, key?.let { "superset.$it" })

    private fun updateSetAt(listIndex: Int, transform: (SupersetSetUi) -> SupersetSetUi) {
        val sets = _uiState.value.sets.toMutableList()
        if (listIndex in sets.indices) {
            sets[listIndex] = transform(sets[listIndex])
            _uiState.value = _uiState.value.copy(sets = sets)
            // Single choke point for set mutations — mirror for process-death restore.
            savedStateHandle[DRAFT_SUPERSET_SETS] = encodeSupersetDraft(sets)
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
        crumb("complete superset $exerciseIds")
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
        crumb("switch member $memberIndex → $newExerciseId")
        viewModelScope.launch {
            val oldExerciseId = exerciseIds.getOrNull(memberIndex) ?: return@launch
            val newExercise = exerciseRepository.getById(newExerciseId) ?: return@launch
            val fresh = workoutRepository.getSession(sessionId, LocalDate.now())
                ?: currentSession ?: return@launch
            // Per-member untouched: same signal the "Salta" label uses —
            // a STRETCH member counts as touched once any of its sets is toggled done.
            val untouched = _uiState.value.sets
                .filter { it.exerciseIndex == memberIndex }
                .none {
                    it.repsTouched || it.weightTouched ||
                        (it.exerciseType == ExerciseType.STRETCH && it.done)
                }
            val updated = fresh.withExerciseSwitched(
                oldExerciseId, newExercise, allowDiscardRecordedSets = untouched,
            )
            if (updated === fresh) return@launch
            workoutRepository.save(updated)
            currentSession = updated
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
        crumb("exit without completing superset $exerciseIds")
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
        // One weigh-in lookup for the whole chain; per-member differences are only the percent
        // (bodyweight) or the per-set offset (assisted).
        val base = if (members.any { it.exercise.isBodyweight || it.exercise.loadMode == LoadMode.ASSISTED })
            sessionBaseWeight(date) else null
        data class Bw(val percent: Int, val weight: Double, val base: Double, val assisted: Boolean)
        val bwByIndex = members.map { m ->
            when {
                m.exercise.isBodyweight -> Bw(
                    percent = m.exercise.bwLoadPercent,
                    weight = materializeBodyweightWeight(m.exercise.bwLoadPercent, base),
                    base = base ?: 0.0,
                    assisted = false,
                )
                m.exercise.loadMode == LoadMode.ASSISTED -> Bw(
                    percent = 0,
                    weight = 0.0, // computed per-set below (assist offset varies per set)
                    base = base ?: 0.0,
                    assisted = true,
                )
                else -> Bw(0, 0.0, 0.0, false)
            }
        }
        fun strengthSet(setUi: SupersetSetUi, bw: Bw) = when {
            bw.percent > 0 -> ExerciseSet.Strength(
                reps = setUi.reps,
                weight = bw.weight,
                isBodyweight = true,
                bwLoadPercent = bw.percent,
                bwBaseWeightKg = bw.base,
            )
            bw.assisted -> ExerciseSet.Strength(
                reps = setUi.reps,
                weight = materializeAssistedWeight(base, setUi.weight),
                isAssisted = true,
                assistOffsetKg = setUi.weight,
                bwBaseWeightKg = bw.base,
            )
            else -> ExerciseSet.Strength(reps = setUi.reps, weight = setUi.weight)
        }
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
            val bw = bwByIndex.getOrElse(mi) { Bw(0, 0.0, 0.0, false) }
            ex.copy(
                completed = completed,
                completedEmpty = completed && !performed,
                sets = memberSets.map { setUi ->
                    // A touched set (or an explicit completion) persists the on-screen value;
                    // an untouched set on back-out keeps its on-disk entry value via
                    // mergeExitSets, so a zero-touch visit can't fabricate recorded data and
                    // lock the member's "Switch exercise" slot (see its KDoc).
                    val touched = setUi.repsTouched || setUi.weightTouched ||
                        (setUi.exerciseType == ExerciseType.STRETCH && setUi.done)
                    val built: ExerciseSet = when (setUi.exerciseType) {
                        ExerciseType.FORZA -> strengthSet(setUi, bw)
                        ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = setUi.timeSeconds, done = setUi.done)
                        ExerciseType.CARDIO -> error("Cardio exercises cannot be superset members")
                    }
                    mergeExitSets(built, touched, completed, ex.sets.getOrNull(setUi.setIndex))
                },
            )
        }
        return copy(exercises = updatedExercises)
    }
}

/**
 * Process-death drafts for superset sets (Sprint 9): one row per set,
 * `"exerciseIndex|setIndex|reps|weight|repsTouched|weightTouched|timeSeconds|done"`.
 * Previous-set references stay out — they reload fresh from the sidecar every init.
 * A length mismatch (chain edited between death and restore) ignores the draft rather
 * than misaligning sets. Pure helpers, unit-tested.
 */
internal const val DRAFT_SUPERSET_SETS = "draft_superset_sets"

internal data class SupersetSetDraft(
    val exerciseIndex: Int,
    val setIndex: Int,
    val reps: Int,
    val weight: Double,
    val repsTouched: Boolean,
    val weightTouched: Boolean,
    val timeSeconds: Int,
    val done: Boolean,
)

internal fun encodeSupersetDraft(sets: List<SupersetSetUi>): ArrayList<String> =
    ArrayList(
        sets.map {
            "${it.exerciseIndex}|${it.setIndex}|${it.reps}|${it.weight}|" +
                "${it.repsTouched}|${it.weightTouched}|${it.timeSeconds}|${it.done}"
        },
    )

internal fun decodeSupersetDraft(raw: List<String>?): List<SupersetSetDraft>? {
    if (raw == null) return null
    return raw.map { row ->
        val p = row.split("|")
        if (p.size != 8) return null
        SupersetSetDraft(
            exerciseIndex = p[0].toIntOrNull() ?: return null,
            setIndex = p[1].toIntOrNull() ?: return null,
            reps = p[2].toIntOrNull() ?: return null,
            weight = p[3].toDoubleOrNull() ?: return null,
            repsTouched = p[4].toBooleanStrictOrNull() ?: return null,
            weightTouched = p[5].toBooleanStrictOrNull() ?: return null,
            timeSeconds = p[6].toIntOrNull() ?: return null,
            done = p[7].toBooleanStrictOrNull() ?: return null,
        )
    }
}

internal fun applySupersetDraft(
    base: List<SupersetSetUi>,
    draft: List<SupersetSetDraft>?,
): List<SupersetSetUi> {
    if (draft == null || draft.size != base.size) return base
    return base.mapIndexed { i, s ->
        val d = draft[i]
        // Positional (same slot, same handle): a member switch re-navigates to a fresh
        // entry with an empty handle, so positions can't straddle different members.
        // Derived flags recompute against the fresh previous, like the loaders do.
        s.copy(
            reps = d.reps,
            weight = d.weight,
            repsTouched = d.repsTouched,
            weightTouched = d.weightTouched,
            repsModified = d.reps != s.previousReps,
            weightModified = d.weight != s.previousWeight,
            timeSeconds = d.timeSeconds,
            done = d.done,
        )
    }
}
