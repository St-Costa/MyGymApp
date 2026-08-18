package com.mygymapp.ui.screen.activeroutine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID
import com.mygymapp.data.model.RoutineExercise
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.bestEstimated1RM
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import android.content.Context
import android.util.Log
import com.mygymapp.data.sync.EcgSyncLedgerRepository
import com.mygymapp.data.sync.EcgSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.sync.SyncLedgerRepository
import com.mygymapp.data.sync.SyncWorker
import com.mygymapp.data.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ActiveRoutineUiState(
    val routineName: String = "",
    val notes: String = "",
    val exercises: List<ActiveExerciseUi> = emptyList(),
    val sessionId: String = "",
    val isLoading: Boolean = true,
    val allCompleted: Boolean = false,
    val totalTonnage: Double = 0.0,
    val previousTonnage: Double? = null,
    val sessionCalories: Double = 0.0,
    val sessionTrimp: Double = 0.0,
    val vo2max: Double = 0.0,
    // Chart: one point per completed session (last 12 weeks), oldest first
    val sessionTonnage: List<Double> = emptyList(),
    val sessionBestE1RM: List<Double> = emptyList(),
    val sessionTonnageByBodypart: Map<String, List<Double>> = emptyMap(),
    val sessionLabels: List<String> = emptyList(),
    val selectedChartFilter: String = "Totale",
    val isLoadingChart: Boolean = false,
    val sessionRegistered: Boolean = false,
    val registeredSessionId: String = "",
    val registeredSessionDate: String = "",
    // Session-RPE prompt: shown once, right after "Registra routine" is tapped and before
    // the session is actually finalized/synced. Skippable — see docs/CONVENTIONS.md.
    val showRpePrompt: Boolean = false,
    // Cross-routine charts (all sessions, not filtered by routine)
    val allSessionCalories: List<Double> = emptyList(),
    val allSessionTrimp: List<Double> = emptyList(),
    val allSessionVo2max: List<Double> = emptyList(),
    val allSessionLabels: List<String> = emptyList(),
    // True when the current week is a configured powerlifting week (shows overlay on open).
    val isPowerliftingWeek: Boolean = false,
    // Set once the user dismisses the powerlifting overlay, so it isn't shown again when
    // returning to this session from an exercise screen. Lives in the VM (not local composable
    // state) so it survives recompositions and navigation.
    val powerliftingDismissed: Boolean = false,
)

/** Section an exercise belongs to within a running session. */
enum class SessionExerciseCategory { WARMUP, DAILY, NORMAL }

data class ActiveExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val type: ExerciseType,
    val bodypart: String,
    val completed: Boolean = false,
    val setCount: Int = 0,
    val tonnageChangePct: Double? = null,
    val rmChangePct: Double? = null,
    val isFirstTimeTonnage: Boolean = false,
    val completedEmpty: Boolean = false,
    val supersetWithNext: Boolean = false,
    val excludeFromTonnage: Boolean = false,
    val category: SessionExerciseCategory = SessionExerciseCategory.NORMAL,
    // "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): set once this slot has been
    // swapped mid-session. substitutedForName is resolved once at switch time so the badge
    // doesn't need a repeated exerciseRepository lookup on every recomposition.
    val substitutedFor: String? = null,
    val substitutedForName: String? = null,
)

@HiltViewModel
class ActiveRoutineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    private val polarManager: PolarManager,
    private val appLogger: AppLogger,
    private val powerliftingScheduleRepository: com.mygymapp.data.PowerliftingScheduleRepository,
    private val syncLedgerRepository: SyncLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val ecgSyncLedgerRepository: EcgSyncLedgerRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveRoutineVM"
    }

    private val routineId: String = savedStateHandle["routineId"] ?: ""

    private val _uiState = MutableStateFlow(ActiveRoutineUiState())
    val uiState: StateFlow<ActiveRoutineUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var previousTonnageByExercise: Map<String, Double> = emptyMap()
    private var previousBestE1RMByExercise: Map<String, Double> = emptyMap()
    // Exercise IDs that have at least one *earlier* session with real tonnage recorded (i.e.
    // not just completed-empty/skipped). Built by scanning each exercise's own history
    // (workoutRepository.getSessionsForExercise), not just the immediately previous session of
    // this routine — a single completed-empty session right before this one must not make a
    // well-tracked exercise look like "first time" again, and conversely an exercise with no
    // history at all must be flagged as first time even if it wasn't in the previous session.
    private var exercisesWithPriorTonnage: Set<String> = emptySet()
    private var sessionFinalized = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            val routine = routineRepository.getById(routineId) ?: return@launch
            _uiState.value = _uiState.value.copy(
                isPowerliftingWeek = powerliftingScheduleRepository.isPowerliftingWeek(),
            )
            // The "Fixed daily exercise" container is a template, not a startable workout.
            if (routineId == FIXED_DAILY_ROUTINE_ID) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                return@launch
            }

            // Build the session in order: warmup -> fixed-daily -> normal.
            // Warmup and fixed-daily exercises are excluded from tonnage (but not cardio).
            val warmup = routine.exercises.filter { it.isWarmup }
            val normal = routine.exercises.filterNot { it.isWarmup }
            val routineExerciseIds = routine.exercises.map { it.exerciseId }.toSet()
            // Skip a fixed-daily exercise already present in the routine (duplicates unsupported).
            val fixed = (routineRepository.getById(FIXED_DAILY_ROUTINE_ID)?.exercises ?: emptyList())
                .filterNot { it.exerciseId in routineExerciseIds }

            // Clear the superset link on each section's last item so no pair spans a boundary.
            fun List<RoutineExercise>.clearTailLink(): List<RoutineExercise> =
                mapIndexed { i, re -> if (i == lastIndex) re.copy(supersetWithNext = false) else re }

            // (RoutineExercise, section) in execution order. Non-normal sections are excluded
            // from tonnage.
            val ordered: List<Pair<RoutineExercise, SessionExerciseCategory>> =
                warmup.clearTailLink().map { it to SessionExerciseCategory.WARMUP } +
                    fixed.clearTailLink().map { it to SessionExerciseCategory.DAILY } +
                    normal.clearTailLink().map { it to SessionExerciseCategory.NORMAL }

            val exercises = ordered.mapNotNull { (re, category) ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                ActiveExerciseUi(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    type = exercise.type,
                    bodypart = exercise.bodypart,
                    setCount = re.sets,
                    supersetWithNext = re.supersetWithNext,
                    excludeFromTonnage = category != SessionExerciseCategory.NORMAL,
                    category = category,
                )
            }

            // Load previous session BEFORE saving the current one, so we don't find ourselves
            val previousSession = workoutRepository.getLastSessionForRoutine(routineId)

            // Pre-compute per-exercise tonnage from previous session (excluded ones don't count)
            previousTonnageByExercise = previousSession?.exercises
                ?.filterNot { it.excludeFromTonnage }
                ?.associate { ex ->
                    ex.exerciseId to ex.sets
                        .filterIsInstance<ExerciseSet.Strength>()
                        .sumOf { it.reps * it.weight }
                } ?: emptyMap()

            previousBestE1RMByExercise = previousSession?.exercises
                ?.filterNot { it.excludeFromTonnage }
                ?.mapNotNull { ex ->
                    ex.sets.filterIsInstance<ExerciseSet.Strength>().bestEstimated1RM()
                        ?.let { ex.exerciseId to it }
                }?.toMap() ?: emptyMap()

            // For each exercise in this routine, check its own history (across all routines,
            // not just this one) for any earlier session with real tonnage recorded. A session
            // where the exercise was completed empty (skipped/untouched) doesn't count — see
            // exercisesWithPriorTonnage doc comment above.
            exercisesWithPriorTonnage = exercises
                .filter { it.type == ExerciseType.FORZA && !it.excludeFromTonnage }
                .filter { ex ->
                    workoutRepository.getSessionsForExercise(ex.exerciseId).any { session ->
                        session.exercises
                            .filter { it.exerciseId == ex.exerciseId && !it.excludeFromTonnage }
                            .any { we ->
                                we.sets.filterIsInstance<ExerciseSet.Strength>()
                                    .sumOf { it.reps * it.weight } > 0.0
                            }
                    }
                }
                .map { it.exerciseId }
                .toSet()

            // Compute previous tonnage using only exercises common to the current session,
            // so it matches the chart (which also filters to current exercise IDs).
            val currentNormalExIds = ordered
                .filter { (_, cat) -> cat == SessionExerciseCategory.NORMAL }
                .map { (re, _) -> re.exerciseId }
                .toSet()
            val commonPreviousTonnage: Double? = previousSession?.exercises
                ?.filter { it.exerciseId in currentNormalExIds && !it.excludeFromTonnage }
                ?.sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }

            // Create and save the workout session
            val workoutExercises = ordered.mapNotNull { (re, category) ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                // Cardio has no pre-configured set count (re.sets is meaningless for it — the
                // routine editor hides that field for CARDIO exercises) — blocks are appended
                // one at a time from CardioExerciseScreen's "Inizia cardio"/"Termina cardio".
                val sets = when (exercise.type) {
                    ExerciseType.FORZA -> (1..re.sets).map {
                        ExerciseSet.Strength(isBodyweight = exercise.isBodyweight)
                    }
                    ExerciseType.STRETCH -> (1..re.sets).map {
                        ExerciseSet.Stretch(timeSeconds = re.timePerSetSeconds)
                    }
                    ExerciseType.CARDIO -> emptyList()
                }
                WorkoutExercise(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    bodypart = exercise.bodypart,
                    type = exercise.type,
                    sets = sets,
                    // Cardio never contributes to tonnage, regardless of section.
                    excludeFromTonnage = exercise.type == ExerciseType.CARDIO ||
                        category != SessionExerciseCategory.NORMAL,
                    isDaily = category == SessionExerciseCategory.DAILY,
                )
            }

            val session = WorkoutSession(
                id = "",
                routineId = routineId,
                routineName = routine.name,
                date = LocalDate.now().toString(),
                startedAt = LocalDateTime.now().toString(),
                exercises = workoutExercises,
                notes = routine.notes,
            )
            val saved = workoutRepository.save(session)
            currentSession = saved

            appLogger.i(TAG, "Session created: id=${saved.id} routine='${routine.name}' exercises=${workoutExercises.size}")
            // Start ECG recording + HR series capture (no-op if Polar not connected)
            polarManager.startEcgRecording(saved.id)
            polarManager.startHrSeriesCapture()
            appLogger.i(TAG, "ECG + HR series capture started for session=${saved.id} polar=${polarManager.connectedDeviceId ?: "not connected"}")

            _uiState.value = ActiveRoutineUiState(
                routineName = routine.name,
                notes = routine.notes,
                exercises = exercises,
                sessionId = saved.id,
                isLoading = false,
                previousTonnage = commonPreviousTonnage,
                // Preserve the flag set above — recreating the state from scratch would reset it.
                isPowerliftingWeek = _uiState.value.isPowerliftingWeek,
            )
        }
    }

    fun markExerciseCompleted(exerciseId: String) {
        viewModelScope.launch {
            // Reload session from disk to get actual set data written by exercise screen
            val session = currentSession ?: return@launch
            val today = LocalDate.parse(session.date)
            val reloaded = workoutRepository.getSession(session.id, today) ?: session

            val reloadedExercise = reloaded.exercises.find { it.exerciseId == exerciseId }
            // The exercise screen itself decides completed=false when the lifter never touched
            // any pre-filled value (see StrengthExerciseViewModel/SupersetViewModel/
            // StretchExerciseViewModel completeExercise()) — honor that here instead of always
            // ticking the row off, so an untouched exercise stays open rather than counting as done.
            if (reloadedExercise?.completed != true) {
                return@launch
            }

            // Compute current tonnage for this exercise
            val currentExTonnage = reloadedExercise
                .sets.filterIsInstance<ExerciseSet.Strength>()
                .sumOf { it.reps * it.weight }

            val prevExTonnage = previousTonnageByExercise[exerciseId]
            val changePct: Double? = if (prevExTonnage != null && prevExTonnage > 0) {
                ((currentExTonnage - prevExTonnage) / prevExTonnage) * 100.0
            } else null
            // True when this is the first time real tonnage has ever been recorded for this
            // exercise (checked against its full history via exercisesWithPriorTonnage, not just
            // the immediately previous session — a completed-empty/skipped previous session must
            // not make a well-tracked exercise look like "first time" again).
            val isFirstTime = exerciseId !in exercisesWithPriorTonnage && currentExTonnage > 0

            val currentBestE1RM = reloadedExercise
                .sets.filterIsInstance<ExerciseSet.Strength>()
                .bestEstimated1RM()
            val prevBestE1RM = previousBestE1RMByExercise[exerciseId]
            val rmChangePct: Double? = if (prevBestE1RM != null && prevBestE1RM > 0 && currentBestE1RM != null) {
                ((currentBestE1RM - prevBestE1RM) / prevBestE1RM) * 100.0
            } else null

            val completedEmpty = reloadedExercise.completedEmpty
            val updatedExercises = _uiState.value.exercises.map { ex ->
                if (ex.exerciseId == exerciseId) {
                    ex.copy(
                        completed = true,
                        completedEmpty = completedEmpty,
                        // No tonnage/1RM comparison for warmup/fixed-daily exercises, or when
                        // completed with no data (nothing to compare — see completedEmpty above).
                        tonnageChangePct = if (ex.type == ExerciseType.FORZA && !ex.excludeFromTonnage && !completedEmpty) changePct else null,
                        rmChangePct = if (ex.type == ExerciseType.FORZA && !ex.excludeFromTonnage && !completedEmpty) rmChangePct else null,
                        isFirstTimeTonnage = ex.type == ExerciseType.FORZA && !ex.excludeFromTonnage && !completedEmpty && isFirstTime,
                    )
                } else ex
            }
            val allCompleted = updatedExercises.all { it.completed }
            _uiState.value = _uiState.value.copy(
                exercises = updatedExercises,
                allCompleted = allCompleted,
            )

            if (allCompleted && !sessionFinalized) {
                finalizeSession(reloaded)
            }
        }
    }

    /**
     * "Switch exercise" (docs/CONVENTIONS.md#switch-exercise): the exercise screen itself
     * already applied the switch to the session file (via its own `switchExercise` call on
     * `StrengthExerciseViewModel`/`StretchExerciseViewModel`/`SupersetViewModel`, using the same
     * shared [WorkoutSession.withExerciseSwitched]) — this VM's job here is only to catch its
     * own in-memory [_uiState] up with that already-durable change, since it was built once at
     * session start and has no way to know about a write that happened from a different VM.
     * Called from [AppNavigation] the same way [markExerciseCompleted] is: as a
     * `savedStateHandle` result observed when the exercise screen is popped/replaced.
     */
    fun applyExerciseSwitch(oldExerciseId: String, newExerciseId: String) {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val today = LocalDate.parse(session.date)
            val reloaded = workoutRepository.getSession(session.id, today) ?: return@launch
            currentSession = reloaded

            val newSlot = reloaded.exercises.find { it.exerciseId == newExerciseId } ?: return@launch

            // Populate an on-demand progression baseline for the new exerciseId, mirroring how
            // exercisesWithPriorTonnage is computed in init — the precomputed
            // previousTonnageByExercise/previousBestE1RMByExercise maps only cover this
            // routine's ORIGINAL exercises, so the switched-in id would otherwise have no
            // baseline at all when markExerciseCompleted looks it up later.
            val lastWithTonnage = workoutRepository.getSessionsForExercise(newExerciseId)
                .firstNotNullOfOrNull { hist ->
                    hist.exercises.firstOrNull {
                        it.exerciseId == newExerciseId && !it.excludeFromTonnage
                    }
                }
            if (lastWithTonnage != null) {
                val tonnage = lastWithTonnage.sets.filterIsInstance<ExerciseSet.Strength>()
                    .sumOf { it.reps * it.weight }
                if (tonnage > 0.0) {
                    previousTonnageByExercise = previousTonnageByExercise + (newExerciseId to tonnage)
                }
                lastWithTonnage.sets.filterIsInstance<ExerciseSet.Strength>().bestEstimated1RM()
                    ?.let { previousBestE1RMByExercise = previousBestE1RMByExercise + (newExerciseId to it) }
                exercisesWithPriorTonnage = exercisesWithPriorTonnage + newExerciseId
            }

            val updatedExercises = _uiState.value.exercises.map { ex ->
                if (ex.exerciseId == oldExerciseId) {
                    ActiveExerciseUi(
                        exerciseId = newSlot.exerciseId,
                        exerciseName = newSlot.exerciseName,
                        type = newSlot.type,
                        bodypart = newSlot.bodypart,
                        setCount = newSlot.sets.size,
                        supersetWithNext = ex.supersetWithNext,
                        excludeFromTonnage = newSlot.excludeFromTonnage,
                        category = ex.category,
                        substitutedFor = oldExerciseId,
                        substitutedForName = ex.exerciseName,
                    )
                } else ex
            }
            _uiState.value = _uiState.value.copy(exercises = updatedExercises)
        }
    }

    fun updateNotes(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            currentSession = session.copy(notes = notes)
            workoutRepository.save(currentSession!!)
            // Also update the routine notes
            val routine = routineRepository.getById(routineId) ?: return@launch
            routineRepository.save(routine.copy(notes = notes))
        }
    }

    fun dismissPowerliftingOverlay() {
        _uiState.value = _uiState.value.copy(powerliftingDismissed = true)
    }

    fun selectChartFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedChartFilter = filter)
    }

    /**
     * Entry point from the "Registra routine" button. Shows the mandatory session-RPE prompt
     * first (docs/SYNC.md — internal-load signal complementing tonnage); the actual
     * finalize+sync flow ([registerRoutine]) runs only after the user answers it, via
     * [submitSessionRpe]. No skip option — a rating is required to proceed.
     */
    fun requestRegisterRoutine() {
        _uiState.value = _uiState.value.copy(showRpePrompt = true)
    }

    /**
     * Submits the session-RPE (Foster method, 0-9) and proceeds to register the session.
     * Validated client-side before being stored: values outside [0, 9] are rejected and the
     * prompt stays open rather than silently clamping or dropping the rating.
     * @return true if accepted, false if invalid (caller should keep the prompt open).
     */
    fun submitSessionRpe(rpe: Int): Boolean {
        if (rpe !in 0..9) return false
        _uiState.value = _uiState.value.copy(showRpePrompt = false)
        registerRoutine(sessionRpe = rpe)
        return true
    }

    /** Session duration in whole minutes from startedAt/completedAt, or null if unavailable. */
    private fun sessionDurationMinutes(session: WorkoutSession): Float? {
        if (session.startedAt.isBlank()) return null
        val end = if (session.completedAt.isNotBlank()) {
            runCatching { LocalDateTime.parse(session.completedAt) }.getOrNull()
        } else {
            LocalDateTime.now()
        } ?: return null
        val start = runCatching { LocalDateTime.parse(session.startedAt) }.getOrNull() ?: return null
        val minutes = java.time.Duration.between(start, end).toMinutes().toFloat()
        return minutes.takeIf { it > 0f }
    }

    private fun registerRoutine(sessionRpe: Int? = null) {
        viewModelScope.launch {
            if (!sessionFinalized) {
                val session = currentSession ?: return@launch
                val today = LocalDate.parse(session.date)
                val reloaded = workoutRepository.getSession(session.id, today) ?: session
                finalizeSession(reloaded)
            }
            // Stop ECG streaming + HR capture, then run post-session analyses.
            polarManager.stopEcgRecording()
            polarManager.stopHrSeriesCapture()
            val session = currentSession
            if (session != null) {
                // Local deep ECG analysis (Pan-Tompkins, RMSSD/SDNN/pNN50/Poincaré,
                // arrhythmia markers) has moved server-side (docs/SYNC.md "Fourth record
                // type: raw ECG") — the phone no longer runs EcgAnalyzer.analyze() here.
                // Only the lightweight, non-ECG-derived metrics are computed locally:
                // resting HR and VO2max (both from live HR/readiness tracking, not the
                // recorded waveform), plus cardiac drift and HRR which are cheap HR-series
                // computations, not heavy waveform analysis. TRIMP/kcal are computed
                // continuously during the session (PolarManager) and saved unchanged below.
                val ecgFileSize = polarManager.ecgFileSize(session.id)
                Log.i(TAG, "ECG file for ${session.id}: $ecgFileSize bytes")
                appLogger.i(TAG, "ECG file size for ${session.id}: $ecgFileSize bytes")
                val drift = try {
                    polarManager.cardiacDriftBpmPerMinute()
                } catch (e: Throwable) {
                    Log.e("ActiveRoutineVM", "Drift compute failed", e)
                    0.0
                }
                val today = LocalDate.parse(session.date)
                val reloaded = workoutRepository.getSession(session.id, today) ?: session
                var updated = reloaded.copy(
                    cardiacDriftBpmMin = drift,
                    hrr60s = polarManager.averageHrr60s(),
                    restingHr = polarManager.sessionRestingHr(),
                )
                if (sessionRpe != null) {
                    val durationMinutes = sessionDurationMinutes(updated)
                    updated = updated.copy(
                        sessionRpe = sessionRpe,
                        sessionLoad = durationMinutes?.let { sessionRpe * it },
                    )
                }
                try {
                    val saved = workoutRepository.save(updated)
                    currentSession = saved
                    // Enqueue for server sync (docs/SYNC.md §1.1) — after the durable save,
                    // never inline. The actual send happens async via WorkManager so a
                    // flaky/offline/unreachable server can never block this flow. Gated on
                    // isEnabled(): this is the *automatic* per-session path, distinct from
                    // the user's explicit "Resync all" action in Options (which enqueues
                    // regardless, since pressing that button is itself the opt-in).
                    if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
                        val relPath = workoutRepository.relPathFor(saved)
                        val file = workoutRepository.fileFor(saved)
                        if (file.exists()) {
                            syncLedgerRepository.enqueue(saved.id, relPath, file)
                            SyncWorker.Scheduler.runExpedited(appContext)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("ActiveRoutineVM", "Save session failed", e)
                }
                // Raw ECG handling (docs/SYNC.md "Fourth record type: raw ECG"): when sync
                // is configured, the raw file is queued for upload and EcgSyncWorker
                // deletes it only after a confirmed SENT — never here. This is what makes
                // the raw waveform available for the server's own analysis (deep ECG
                // analysis no longer runs on the phone at all, see above), not just the
                // lightweight metrics computed locally.
                // When sync isn't configured/enabled, fall back to deleting the file
                // immediately — with no local analysis and no server to send it to, there's
                // nothing left that would ever consume it, so there's no reason to keep it.
                // (Previously this branch was gated on ecgResult.hasAnything — i.e. "keep
                // only if local analysis failed, for offline inspection." That no longer
                // applies since local analysis doesn't run.)
                if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
                    val ecgFile = polarManager.ecgFileFor(session.id)
                    if (ecgFile.exists()) {
                        try {
                            // Ledger records the hash of the raw bytes here just to mark
                            // "queued"; EcgSyncWorker recomputes the hash over the actual
                            // gzip-compressed bytes it transmits and updates the entry
                            // (via markSent/markFailed) — see EcgSyncWorker.doWork().
                            ecgSyncLedgerRepository.enqueue(session.id, "ecg/${session.id}.ecg", ecgFile.readBytes())
                            EcgSyncWorker.Scheduler.runExpedited(appContext)
                        } catch (e: Throwable) {
                            Log.e(TAG, "ECG sync enqueue failed for ${session.id}: ${e.message}", e)
                        }
                    }
                } else {
                    try { polarManager.deleteEcgFile(session.id) } catch (_: Throwable) {}
                }
            }
            appLogger.i(TAG, "Session registered: id=${session?.id} tonnage=${session?.totalTonnage} kcal=${"%.1f".format(session?.sessionCalories ?: 0.0)} trimp=${"%.1f".format(session?.sessionTrimp ?: 0.0)}")
            _uiState.value = _uiState.value.copy(
                sessionRegistered = true,
                registeredSessionId = session?.id ?: "",
                registeredSessionDate = session?.date ?: "",
            )
        }
    }

    fun abandonSession() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
            appLogger.w(TAG, "Session abandoned by user: id=${session.id}")
            polarManager.stopEcgRecording()
            polarManager.stopHrSeriesCapture()
            polarManager.deleteEcgFile(session.id)
            workoutRepository.delete(session)
        }
    }

    private fun finalizeSession(reloaded: WorkoutSession) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingChart = true)

            val updated = reloaded.copy(
                completedAt = LocalDateTime.now().toString(),
            )
            // Calculate tonnage from actual set data
            var totalTonnage = 0.0
            val tonnageByBodypart = mutableMapOf<String, Double>()
            for (ex in updated.exercises) {
                // Warmup + fixed-daily exercises never contribute to tonnage.
                if (ex.excludeFromTonnage) continue
                var exTonnage = 0.0
                for (set in ex.sets) {
                    if (set is ExerciseSet.Strength) {
                        exTonnage += set.reps * set.weight
                    }
                }
                totalTonnage += exTonnage
                tonnageByBodypart[ex.bodypart] =
                    (tonnageByBodypart[ex.bodypart] ?: 0.0) + exTonnage
            }
            val finalSession = updated.copy(
                totalTonnage = totalTonnage,
                tonnageByBodypart = tonnageByBodypart.filterValues { it > 0.0 },
                sessionCalories = polarManager.sessionCalories.value,
                sessionTrimp = polarManager.sessionTrimp.value,
                vo2max = polarManager.vo2max.value ?: 0.0,
            )
            currentSession = finalSession
            workoutRepository.save(finalSession)

            // Load all sessions for this routine in the last 12 weeks, one point per session
            val today = LocalDate.now()
            val startDate = today.with(DayOfWeek.MONDAY).minusWeeks(11)
            val allSessions = workoutRepository.getSessionsInRange(startDate, today)
                .filter { it.routineId == routineId && it.completedAt.isNotBlank() }  // exclude abandoned sessions

            val labelFmt = DateTimeFormatter.ofPattern("d/M")
            val sessionLabels = allSessions.map { LocalDate.parse(it.date).format(labelFmt) }

            // Compare only exercises that are in the current session, so the chart is meaningful
            // even when routine composition has changed between sessions.
            val currentForza = finalSession.exercises.filter { it.type == ExerciseType.FORZA && !it.excludeFromTonnage }
            val currentExerciseIds = currentForza.map { it.exerciseId }.toSet()
            val sessionTonnage = allSessions.map { hist ->
                hist.exercises
                    .filter { it.exerciseId in currentExerciseIds && !it.excludeFromTonnage }
                    .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
            }

            // Best estimated 1RM per session, across the same exercises used for sessionTonnage.
            // Unlike tonnage, 1RM is a max across exercises/sets, not a sum — summing would just
            // reproduce the same "more reps beats heavier weight" distortion this metric exists to avoid.
            val sessionBestE1RM = allSessions.map { hist ->
                hist.exercises
                    .filter { it.exerciseId in currentExerciseIds && !it.excludeFromTonnage }
                    .mapNotNull { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().bestEstimated1RM() }
                    .maxOrNull() ?: 0.0
            }

            // Only bodyparts with strength exercises in the current session
            val bodyparts = currentForza.map { it.bodypart }.distinct()
            val sessionTonnageByBodypart = bodyparts.associateWith { bp ->
                val bpIds = currentForza.filter { it.bodypart == bp }.map { it.exerciseId }.toSet()
                allSessions.map { hist ->
                    hist.exercises
                        .filter { it.exerciseId in bpIds && !it.excludeFromTonnage }
                        .sumOf { ex -> ex.sets.filterIsInstance<ExerciseSet.Strength>().sumOf { it.reps * it.weight } }
                }
            }

            // Cross-routine data: ALL completed sessions for kcal/TRIMP/VO2max charts
            val allCompletedSessions = workoutRepository.getSessionsInRange(startDate, today)
                .filter { it.completedAt.isNotBlank() }
            val allLabels = allCompletedSessions.map { LocalDate.parse(it.date).format(labelFmt) }
            val allCalories = allCompletedSessions.map { it.sessionCalories }
            val allTrimp = allCompletedSessions.map { it.sessionTrimp }
            val allVo2 = allCompletedSessions.map { it.vo2max }

            sessionFinalized = true
            _uiState.value = _uiState.value.copy(
                totalTonnage = totalTonnage,
                sessionCalories = finalSession.sessionCalories,
                sessionTrimp = finalSession.sessionTrimp,
                vo2max = finalSession.vo2max,
                sessionTonnage = sessionTonnage,
                sessionBestE1RM = sessionBestE1RM,
                sessionTonnageByBodypart = sessionTonnageByBodypart,
                sessionLabels = sessionLabels,
                allSessionCalories = allCalories,
                allSessionTrimp = allTrimp,
                allSessionVo2max = allVo2,
                allSessionLabels = allLabels,
                selectedChartFilter = "Totale",
                isLoadingChart = false,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        val session = currentSession
        if (session == null) {
            clearScope.cancel()
            return
        }
        // Always stop the live ECG/HR capture if the user leaves without registering —
        // otherwise the Polar stream keeps running and writing to the .ecg file until
        // the device disconnects.
        val needsGhostCleanup = !sessionFinalized
        if (needsGhostCleanup) {
            appLogger.w(TAG, "onCleared without registration: id=${session.id} — stopping streams, checking ghost cleanup")
        }
        clearScope.launch {
            try {
                polarManager.stopEcgRecording()
                polarManager.stopHrSeriesCapture()
                if (needsGhostCleanup) {
                    val today = LocalDate.parse(session.date)
                    val reloaded = workoutRepository.getSession(session.id, today) ?: session
                    val hasCompleted = reloaded.exercises.any { it.completed }
                    val hasRealSetData = reloaded.exercises.any { ex -> !ex.hasNoRecordedSets() }
                    if (reloaded.completedAt.isBlank() && !hasCompleted && !hasRealSetData) {
                        appLogger.w(TAG, "Ghost session deleted on exit: id=${session.id}")
                        polarManager.deleteEcgFile(session.id)
                        workoutRepository.delete(reloaded)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Session cleanup failed", e)
            } finally {
                clearScope.cancel()
            }
        }
    }
}
