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
import com.mygymapp.data.sync.EcgSyncLedgerRepository
import com.mygymapp.data.sync.EcgSyncWorker
import com.mygymapp.data.sync.ReadinessSyncWorker
import com.mygymapp.data.sync.RepoSyncWorker
import com.mygymapp.data.sync.ScaleWeighInSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.sync.SyncLedgerRepository
import com.mygymapp.data.sync.SyncWorker
import com.mygymapp.data.util.AppLogger
import com.mygymapp.ui.util.filterStitchingSupersetLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ActiveRoutineUiState(
    val routineName: String = "",
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
    // Session-RPE prompt: shown once, right after "Registra" is tapped and before
    // the session is actually finalized/synced. Skippable — see docs/CONVENTIONS.md.
    val showRpePrompt: Boolean = false,
    // The RPE chip selection (0-9, null = nothing picked yet). Lives in the VM — not a
    // local composable remember — so a process kill with the prompt open loses only the
    // dialog, not the pick (mirrored to the SavedStateHandle, Sprint 9).
    val selectedRpe: Int? = null,
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

/**
 * Variazione percentuale del tonnellaggio rispetto al baseline, o null quando non c'è un
 * baseline confrontabile (primo dato). Pura, così è testabile senza ViewModel.
 */
internal fun tonnageChangePct(current: Double, previous: Double?): Double? =
    if (previous != null && previous > 0) ((current - previous) / previous) * 100.0 else null

/**
 * Variazione percentuale del miglior e1RM stimato rispetto al baseline, o null quando
 * manca uno dei due termini. Pura, così è testabile senza ViewModel.
 */
internal fun rmChangePct(current: Double?, previous: Double?): Double? =
    if (previous != null && previous > 0 && current != null) {
        ((current - previous) / previous) * 100.0
    } else {
        null
    }

/** Baseline di progressione (tonnellaggio + miglior e1RM) contro cui il badge confronta. */
internal data class SwitchBaseline(val tonnage: Double?, val bestE1RM: Double?)

/**
 * Risolve il riferimento del badge per uno slot sostituito: il confronto è sempre contro
 * l'esercizio *sostituito* (il pianificato), così c'è una comparazione anche quando i due
 * esercizi sono diversi — ha senso perché gli switch candidati sono stesso bodypart+tipo, e
 * i tonnellaggi sono comunque pesi netti materializzati, quindi confrontabili. Solo quando
 * il sostituito non ha alcuno storico si ripiega su quello proprio del subentrato (che a
 * sua volta può mancare: allora la riga mostra "primo dato"). Pura, testabile senza ViewModel.
 */
internal fun resolveSwitchBaseline(
    replacedTonnage: Double?,
    replacedBestE1RM: Double?,
    ownTonnage: Double?,
    ownBestE1RM: Double?,
): SwitchBaseline =
    if (replacedTonnage != null) {
        SwitchBaseline(replacedTonnage, replacedBestE1RM)
    } else {
        SwitchBaseline(ownTonnage, ownBestE1RM)
    }

data class ActiveExerciseUi(
    val exerciseId: String,
    val exerciseName: String,
    val type: ExerciseType,
    val bodypart: String,
    // Current config: bodyweight exercise (materialized `weight`) vs manual-load. The
    // change badge only compares against previous sessions with the same approach — see
    // ExerciseStats.SCHEMA_VERSION v4. loadMode covers the third approach (ASSISTED) too;
    // isBodyweight is kept for existing readers.
    val isBodyweight: Boolean = false,
    val loadMode: com.mygymapp.data.model.LoadMode = com.mygymapp.data.model.LoadMode.MANUAL,
    val completed: Boolean = false,
    val setCount: Int = 0,
    // Configured block duration for CARDIO exercises (RoutineExercise.timePerSetSeconds).
    // Unused for other types.
    val timePerSetSeconds: Int = 0,
    val tonnageChangePct: Double? = null,
    val rmChangePct: Double? = null,
    val isFirstTimeTonnage: Boolean = false,
    // Total seconds of stretch actually performed this session (sum of `timeSeconds` over
    // the sets the lifter toggled done). Set once a STRETCH exercise is completed with real
    // work — the active-routine row shows this in place of the tonnage/1RM badge that FORZA
    // exercises get. Null for non-stretch, or a stretch closed empty.
    val stretchTotalSeconds: Int? = null,
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

/** SavedStateHandle key for the staged RPE pick (Sprint 9 process-death drafts). */
internal const val DRAFT_RPE = "draft_rpe"

@HiltViewModel
class ActiveRoutineViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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

    private val _uiState = MutableStateFlow(
        ActiveRoutineUiState(selectedRpe = savedStateHandle.get<Int>(DRAFT_RPE)),
    )
    val uiState: StateFlow<ActiveRoutineUiState> = _uiState

    /**
     * One-shot navigation event for session registration, sent alongside the
     * `sessionRegistered` flag. The screen collects this (not the boolean) — same
     * reasoning as `ExerciseSessionViewModel.completionEvent`. Channel-backed (not
     * `SharedFlow` extra-buffer: extra slots don't reach late subscribers, replay would
     * re-fire after rotation) so the pair is consumed exactly once.
     */
    private val _sessionRegisteredEvent = Channel<Pair<String, String>>(Channel.BUFFERED)
    val sessionRegisteredEvent: Flow<Pair<String, String>> = _sessionRegisteredEvent.receiveAsFlow()

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
    // Serializza applyExerciseSwitch e markExerciseCompleted: al rientro da un esercizio appena
    // sostituito E completato, AppNavigation recapita entrambi i savedStateHandle-result
    // ("switchedExerciseIds" + "completedExerciseId") nella stessa ricomposizione e i due handler
    // partirebbero in concorrenza facendo read-modify-write sulla stessa _uiState.exercises —
    // il completamento non trova ancora il nuovo id (o viene sovrascritto dallo switch con
    // completed=false) e la riga resta "da eseguire" pur col disco corretto. Vedi
    // docs/CONVENTIONS.md#switch-exercise ("Switch + complete nella stessa navigazione").
    private val completionMutex = Mutex()

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

            // Build the session in order: fixed-daily -> warmup -> normal.
            // Warmup and fixed-daily exercises are excluded from tonnage (but not cardio).
            val warmup = routine.exercises.filter { it.isWarmup }
            val normal = routine.exercises.filterNot { it.isWarmup }
            val routineExerciseIds = routine.exercises.map { it.exerciseId }.toSet()

            // IDs referenced by any section that resolve to a real exercise. A routine can
            // outlive an exercise it references; such a slot must be dropped.
            val existingExerciseIds = buildSet {
                val candidateIds = (routine.exercises +
                    routineRepository.getById(FIXED_DAILY_ROUTINE_ID)?.exercises.orEmpty())
                    .map { it.exerciseId }
                    .distinct()
                for (id in candidateIds) {
                    if (exerciseRepository.getById(id) != null) add(id)
                }
            }

            // Drop the elements failing `keep` and stitch the survivors of a chain back
            // together — a survivor keeps its forward link when the next survivor belongs to
            // the same original chain (every flag in between was true). Dropping the middle
            // of a 3-chain (daily A links->B links->C with B also in the routine) yields
            // A links->C in the daily section instead of two singles; a link is still
            // cleared across an original chain boundary and on the new tail so no pair
            // spans chains or sections. See filterStitchingSupersetLinks. Used to remove a
            // fixed-daily exercise that's also in the routine, and a routine's references
            // to deleted exercises.
            fun List<RoutineExercise>.filterStitchingLinks(
                keep: (RoutineExercise) -> Boolean,
            ): List<RoutineExercise> = filterStitchingSupersetLinks(
                items = this,
                linked = { it.supersetWithNext },
                linkOff = { it.copy(supersetWithNext = false) },
                keep = keep,
            )

            // Skip a fixed-daily exercise already present in the routine (duplicates unsupported).
            val fixed = (routineRepository.getById(FIXED_DAILY_ROUTINE_ID)?.exercises ?: emptyList())
                .filterStitchingLinks {
                    it.exerciseId !in routineExerciseIds && it.exerciseId in existingExerciseIds
                }

            // Clear the superset link on each section's last item so no pair spans a boundary.
            fun List<RoutineExercise>.clearTailLink(): List<RoutineExercise> =
                mapIndexed { i, re -> if (i == lastIndex) re.copy(supersetWithNext = false) else re }

            // (RoutineExercise, section) in execution order. Non-normal sections are excluded
            // from tonnage.
            val ordered: List<Pair<RoutineExercise, SessionExerciseCategory>> =
                fixed.clearTailLink().map { it to SessionExerciseCategory.DAILY } +
                    warmup.filterStitchingLinks { it.exerciseId in existingExerciseIds }
                        .clearTailLink().map { it to SessionExerciseCategory.WARMUP } +
                    normal.filterStitchingLinks { it.exerciseId in existingExerciseIds }
                        .clearTailLink().map { it to SessionExerciseCategory.NORMAL }

            val exercises = ordered.mapNotNull { (re, category) ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                ActiveExerciseUi(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    type = exercise.type,
                    bodypart = exercise.bodypart,
                    isBodyweight = exercise.isBodyweight,
                    loadMode = exercise.loadMode,
                    setCount = re.sets,
                    timePerSetSeconds = re.timePerSetSeconds,
                    supersetWithNext = re.supersetWithNext,
                    excludeFromTonnage = category != SessionExerciseCategory.NORMAL,
                    category = category,
                )
            }

            // Load previous session BEFORE saving the current one, so we don't find ourselves.
            // Used only for `commonPreviousTonnage` (the chart-consistency number) below — the
            // per-exercise change badges are seeded from the stats sidecar instead, further
            // down, so they can compare like-with-like (same slot context AND same weighting
            // approach) rather than blindly against whatever the last routine session held.
            val previousSession = workoutRepository.getLastSessionForRoutine(routineId)

            // Seeded from the stats sidecar once `statsByExercise` is built (below).
            previousTonnageByExercise = emptyMap()
            previousBestE1RMByExercise = emptyMap()

            // For every strength exercise in this routine, warm its stats sidecar now — in
            // parallel. Two reasons: (1) `exercisesWithPriorTonnage` below needs the NORMAL
            // context's `hasPriorRealTonnage`; (2) pre-building here (including warmup/daily
            // slots, which `exercisesWithPriorTonnage` filters out) means the strength /
            // superset screen opened next reads a ready sidecar instead of doing its own
            // first-time history scan mid-open — which is what made a 3-member superset feel
            // slow the first time. getExerciseStats scans lock-free, so N of these overlap.
            val statsExerciseIds = exercises
                .filter { it.type == ExerciseType.FORZA }
                .map { it.exerciseId }
                .distinct()
            // Cap the fan-out: on a fresh install / schema bump every one of these is a full
            // per-exercise history scan, and letting a 12-exercise routine launch 12 at once
            // (on top of boot maintenance) is an I/O thundering herd. 4 in flight keeps the
            // overlap benefit without the spike.
            val statsGate = kotlinx.coroutines.sync.Semaphore(4)
            val statsByExercise = statsExerciseIds
                .map { exId ->
                    async { statsGate.withPermit { exId to workoutRepository.getExerciseStats(exId) } }
                }
                .awaitAll()
                .toMap()

            // A session where the exercise was completed empty (skipped/untouched) never sets
            // hasPriorRealTonnage — see the exercisesWithPriorTonnage doc comment above.
            // For a DAILY strength slot, "previous" and "first time" are read from the sidecar's
            // DAILY context (not the previous routine session, which may not even contain the
            // fixed-daily exercise) — same like-with-like rule the strength screen itself uses.
            // "First time" here means "first time with this weighting approach": an exercise
            // just switched manual↔bodyweight has no like-with-like previous, so it reads as
            // "primo dato" rather than showing a bogus change (schema v4).
            exercisesWithPriorTonnage = exercises
                .filter { it.type == ExerciseType.FORZA }
                .filter { ex ->
                    val ctx = if (ex.category == SessionExerciseCategory.DAILY) {
                        com.mygymapp.data.model.SlotContext.DAILY
                    } else if (ex.excludeFromTonnage) {
                        return@filter false // warmup — never gets a change badge
                    } else {
                        com.mygymapp.data.model.SlotContext.NORMAL
                    }
                    val cs = statsByExercise[ex.exerciseId]?.forContext(ctx) ?: return@filter false
                    val prevForApproach = cs.previousSetsFor(ex.loadMode)
                    prevForApproach.isNotEmpty()
                }
                .map { it.exerciseId }
                .toSet()

            // Seed the previous-tonnage / previous-1RM maps (they drive the change badge shown
            // next to each completed exercise) from the stats sidecar. Each strength slot reads
            // its own slot context (DAILY slots → DAILY context, everything else that gets a
            // badge → NORMAL) AND its own weighting approach: a bodyweight-configured exercise
            // compares only against previous bodyweight sessions, a manual-load one only against
            // manual-load sessions. When there's no like-with-like history the exercise gets no
            // seed here and renders as "primo dato" — this is what stops a manual↔bodyweight
            // switch from producing +5000% badges (see ExerciseStats.SCHEMA_VERSION v4).
            exercises
                .filter { it.type == ExerciseType.FORZA }
                .filter { it.category == SessionExerciseCategory.DAILY || !it.excludeFromTonnage }
                .forEach { ex ->
                    val ctx = if (ex.category == SessionExerciseCategory.DAILY) {
                        com.mygymapp.data.model.SlotContext.DAILY
                    } else {
                        com.mygymapp.data.model.SlotContext.NORMAL
                    }
                    val cs = statsByExercise[ex.exerciseId]?.forContext(ctx)
                    val prevSets = cs?.previousSetsFor(ex.loadMode).orEmpty()
                        .map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
                    if (prevSets.isNotEmpty()) {
                        previousTonnageByExercise = previousTonnageByExercise +
                            (ex.exerciseId to prevSets.sumOf { it.reps * it.weight })
                        prevSets.bestEstimated1RM()?.let {
                            previousBestE1RMByExercise = previousBestE1RMByExercise + (ex.exerciseId to it)
                        }
                    }
                }

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
                // one at a time from CardioExerciseScreen's "Inizia"/"Termina".
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
        appLogger.d(TAG, "Exercise completed: $exerciseId")
        viewModelScope.launch {
            completionMutex.withLock {
                // Reload session from disk to get actual set data written by exercise screen
                val session = currentSession ?: return@withLock
                val today = LocalDate.parse(session.date)
                val reloaded = workoutRepository.getSession(session.id, today) ?: session

                val reloadedExercise = reloaded.exercises.find { it.exerciseId == exerciseId }
                // A "Complete" tap always closes the exercise out (completed=true), whether or not
                // the lifter touched a value — an untouched one comes back completedEmpty=true and
                // renders as "skipped" (grey border + X), handled below. Only bail if the reload
                // genuinely shows it still open (e.g. this fired without a preceding complete).
                // Nota: con uno switch appena applicato la lista in memoria può non contenere
                // ancora questo id (applyExerciseSwitch deve ancora girare) — in quel caso la map
                // sotto è un no-op intenzionale: sarà applyExerciseSwitch a riportare il completed
                // dal disco, così i due ordini convergono allo stesso stato.
                if (reloadedExercise?.completed != true) {
                    return@withLock
                }

                val updatedExercises = _uiState.value.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.withCompletionFrom(reloadedExercise)
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
    }

    /**
     * Applica a questa riga lo stato di completamento letto dal disco ([reloadedExercise]):
     * flag completed/completedEmpty + badge tonnellaggio/1RM (solo slot FORZA con baseline
     * like-with-like) + secondi eseguiti (solo STRETCH). Condivisa da [markExerciseCompleted] e
     * da [applyExerciseSwitch] (che deve riportare un completed già su disco quando lo switch
     * e il completamento arrivano nella stessa navigazione) così i due percorsi non divergono.
     */
    private fun ActiveExerciseUi.withCompletionFrom(
        reloadedExercise: WorkoutExercise,
    ): ActiveExerciseUi {
        // Compute current tonnage for this exercise
        val currentExTonnage = reloadedExercise
            .sets.filterIsInstance<ExerciseSet.Strength>()
            .sumOf { it.reps * it.weight }

        val currentBestE1RM = reloadedExercise
            .sets.filterIsInstance<ExerciseSet.Strength>()
            .bestEstimated1RM()

        // Total stretch seconds actually performed = sum of `timeSeconds` over the sets the
        // lifter toggled done. Shown in the active-routine row for STRETCH exercises (daily
        // and normal alike) in place of the tonnage/1RM badge.
        val stretchDoneSeconds = reloadedExercise
            .sets.filterIsInstance<ExerciseSet.Stretch>()
            .filter { it.done }
            .sumOf { it.timeSeconds }

        val completedEmpty = reloadedExercise.completedEmpty
        // Tonnage/1RM change is shown for NORMAL and DAILY strength slots (each
        // compared against its own like-with-like history), but not for warmup
        // slots, and never when the exercise was completed with no data.
        val strengthWithBadge = type == ExerciseType.FORZA && !completedEmpty &&
            (category != SessionExerciseCategory.WARMUP)
        return copy(
            completed = true,
            completedEmpty = completedEmpty,
            tonnageChangePct = if (strengthWithBadge) {
                tonnageChangePct(currentExTonnage, previousTonnageByExercise[exerciseId])
            } else {
                null
            },
            rmChangePct = if (strengthWithBadge) {
                rmChangePct(currentBestE1RM, previousBestE1RMByExercise[exerciseId])
            } else {
                null
            },
            // True when this is the first time real tonnage has ever been recorded for this
            // exercise (checked against its full history via exercisesWithPriorTonnage, not just
            // the immediately previous session — a completed-empty/skipped previous session must
            // not make a well-tracked exercise look like "first time" again).
            isFirstTimeTonnage = strengthWithBadge &&
                exerciseId !in exercisesWithPriorTonnage && currentExTonnage > 0,
            stretchTotalSeconds = if (type == ExerciseType.STRETCH && !completedEmpty) {
                stretchDoneSeconds
            } else {
                null
            },
        )
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
        appLogger.d(TAG, "Exercise switch: $oldExerciseId → $newExerciseId")
        viewModelScope.launch {
            completionMutex.withLock {
                val session = currentSession ?: return@withLock
                val today = LocalDate.parse(session.date)
                val reloaded = workoutRepository.getSession(session.id, today) ?: return@withLock
                currentSession = reloaded

                val newSlot = reloaded.exercises.find { it.exerciseId == newExerciseId } ?: return@withLock
                val newExercise = exerciseRepository.getById(newExerciseId)
                val newExerciseIsBodyweight = newExercise?.isBodyweight ?: false
                val newExerciseLoadMode =
                    newExercise?.loadMode ?: com.mygymapp.data.model.LoadMode.MANUAL

                // Baseline di progressione per il nuovo exerciseId: il confronto è contro
                // l'esercizio *sostituito* (il pianificato), non contro lo storico proprio del
                // subentrato — così il badge tonnellaggio/1RM c'è anche quando i due esercizi
                // sono diversi (i candidati sono stesso bodypart+tipo e i pesi sono netti
                // materializzati, quindi confrontabili). Solo se il sostituito non ha alcuno
                // storico si ripiega su quello proprio del subentrato, letto dal sidecar come
                // in init — contesto DAILY per uno slot fixed-daily (confronto like-with-like
                // solo con sessioni daily), NORMAL altrimenti — e approccio di carico
                // like-with-like: uno slot completed-empty non contribuisce per costruzione, e
                // uno cross-approccio non genera mai un confronto fasullo (schema v5/v6).
                val switchSlotContext = if (newSlot.isDaily) {
                    com.mygymapp.data.model.SlotContext.DAILY
                } else {
                    com.mygymapp.data.model.SlotContext.NORMAL
                }
                val switchedStats = workoutRepository.getExerciseStats(newExerciseId)
                    .forContext(switchSlotContext)
                val switchedPrevSets = switchedStats?.previousSetsFor(newExerciseLoadMode).orEmpty()
                    .map { ExerciseSet.Strength(reps = it.reps, weight = it.weight) }
                val ownTonnage = switchedPrevSets.takeIf { it.isNotEmpty() }
                    ?.sumOf { it.reps * it.weight }
                val ownBestE1RM = switchedPrevSets.takeIf { it.isNotEmpty() }
                    ?.bestEstimated1RM()
                val baseline = resolveSwitchBaseline(
                    replacedTonnage = previousTonnageByExercise[oldExerciseId],
                    replacedBestE1RM = previousBestE1RMByExercise[oldExerciseId],
                    ownTonnage = ownTonnage,
                    ownBestE1RM = ownBestE1RM,
                )
                baseline.tonnage?.let {
                    previousTonnageByExercise = previousTonnageByExercise + (newExerciseId to it)
                }
                baseline.bestE1RM?.let {
                    previousBestE1RMByExercise = previousBestE1RMByExercise + (newExerciseId to it)
                }
                if (baseline.tonnage != null &&
                    (oldExerciseId in exercisesWithPriorTonnage || ownTonnage != null)
                ) {
                    exercisesWithPriorTonnage = exercisesWithPriorTonnage + newExerciseId
                }

                val updatedExercises = _uiState.value.exercises.map { ex ->
                    if (ex.exerciseId == oldExerciseId) {
                        val base = ActiveExerciseUi(
                            exerciseId = newSlot.exerciseId,
                            exerciseName = newSlot.exerciseName,
                            type = newSlot.type,
                            bodypart = newSlot.bodypart,
                            isBodyweight = newExerciseIsBodyweight,
                            loadMode = newExerciseLoadMode,
                            setCount = newSlot.sets.size,
                            supersetWithNext = ex.supersetWithNext,
                            excludeFromTonnage = newSlot.excludeFromTonnage,
                            category = ex.category,
                            substitutedFor = oldExerciseId,
                            substitutedForName = ex.exerciseName,
                        )
                        // Switch + complete nella stessa navigazione: il reload dal disco vede
                        // già completed=true (la write del Complete è atterrata prima del pop),
                        // quindi la riga nasce già completata col badge — altrimenti,
                        // nell'ordine mark-prima-apply, il completamento andrebbe perso e la riga
                        // resterebbe "da eseguire" pur col disco corretto.
                        if (newSlot.completed) base.withCompletionFrom(newSlot) else base
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
    }

    fun dismissPowerliftingOverlay() {
        _uiState.value = _uiState.value.copy(powerliftingDismissed = true)
    }

    fun selectChartFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedChartFilter = filter)
    }

    /**
     * Entry point from the "Registra" button. Shows the mandatory session-RPE prompt
     * first (docs/SYNC.md — internal-load signal complementing tonnage); the actual
     * finalize+sync flow ([registerRoutine]) runs only after the user answers it, via
     * [submitSessionRpe]. No skip option — a rating is required to proceed.
     */
    fun requestRegisterRoutine() {
        appLogger.d(TAG, "Register routine requested (RPE prompt)")
        _uiState.value = _uiState.value.copy(showRpePrompt = true)
    }

    /**
     * Submits the session-RPE (Foster method, 0-9) and proceeds to register the session.
     * Validated client-side before being stored: values outside [0, 9] are rejected and the
     * prompt stays open rather than silently clamping or dropping the rating.
     * @return true if accepted, false if invalid (caller should keep the prompt open).
     */
    /** RPE chip tap: staged in state (and the handle) until [submitSessionRpe]. */
    fun onRpeSelected(value: Int) {
        val coerced = value.coerceIn(0, 9)
        _uiState.value = _uiState.value.copy(selectedRpe = coerced)
        savedStateHandle[DRAFT_RPE] = coerced
    }

    fun submitSessionRpe(rpe: Int): Boolean {
        if (rpe !in 0..9) return false
        appLogger.d(TAG, "Session RPE submitted: $rpe")
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
                appLogger.i(TAG, "ECG file size for ${session.id}: $ecgFileSize bytes")
                val drift = try {
                    polarManager.cardiacDriftBpmPerMinute()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    appLogger.e(TAG, "Drift compute failed", e)
                    0.0
                }
                val hrrDiscarded = polarManager.hrrDiscardedCount()
                appLogger.i(TAG, "HRR60s for ${session.id}: discarded $hrrDiscarded delta(s) (HR rebounded before +60s sample)")
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
                    // Enqueue for server sync (docs/SYNC.md §1.1/§1.5) — after the durable
                    // save, never inline. End-of-session is the one point that uploads
                    // regardless of the "Sincronizzazione attiva" toggle (only isConfigured()
                    // gates it), and it flushes *everything* queued: the session itself plus
                    // any readiness / scale / repo / ECG entries sitting in their ledgers.
                    // The forced runExpedited(force = true) bypasses the toggle for this run
                    // (see shouldSyncRun / docs/SYNC.md §1.5).
                    if (syncConfigRepository.isConfigured()) {
                        val relPath = workoutRepository.relPathFor(saved)
                        val file = workoutRepository.fileFor(saved)
                        if (file.exists()) {
                            syncLedgerRepository.enqueue(saved.id, relPath, file)
                        }
                        SyncWorker.Scheduler.runExpedited(appContext, force = true)
                        RepoSyncWorker.Scheduler.runExpedited(appContext, force = true)
                        com.mygymapp.data.sync.ReadinessSyncWorker.Scheduler.runExpedited(appContext, force = true)
                        com.mygymapp.data.sync.ScaleWeighInSyncWorker.Scheduler.runExpedited(appContext, force = true)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    appLogger.e(TAG, "Save session failed", e)
                }
                // Raw ECG handling (docs/SYNC.md "Fourth record type: raw ECG"): if a sync
                // server is *configured*, the raw file is queued and EcgSyncWorker deletes
                // it only after a confirmed SENT — never here. The "Sincronizzazione attiva"
                // toggle no longer matters for this: end-of-session forces the upload
                // (force = true) whether the toggle is on or off (docs/SYNC.md §1.5). Only a
                // phone with *no server configured at all* falls back to deleting the .ecg
                // immediately — nothing local would ever consume it.
                if (syncConfigRepository.isConfigured()) {
                    val ecgFile = polarManager.ecgFileFor(session.id)
                    if (ecgFile.exists()) {
                        try {
                            // Ledger records the hash of the raw bytes here just to mark
                            // "queued"; EcgSyncWorker recomputes the hash over the actual
                            // gzip-compressed bytes it transmits and updates the entry
                            // (via markSent/markFailed) — see EcgSyncWorker.doWork().
                            // readBytes() is ~1 MB of file I/O: off the Main thread. The
                            // await first: stopEcgRecording() above only *queues* the
                            // drain, and reading before it lands would miss the tail.
                            val ecgBytes = withContext(Dispatchers.IO) {
                                polarManager.awaitEcgIdle()
                                ecgFile.readBytes()
                            }
                            ecgSyncLedgerRepository.enqueue(session.id, "ecg/${session.id}.ecg", ecgBytes)
                            EcgSyncWorker.Scheduler.runExpedited(appContext, force = true)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            appLogger.e(TAG, "ECG sync enqueue failed for ${session.id}: ${e.message}", e)
                        }
                    }
                } else {
                    try { polarManager.deleteEcgFile(session.id) } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                    }
                }
            }
            appLogger.i(TAG, "Session registered: id=${session?.id} tonnage=${session?.totalTonnage} kcal=${"%.1f".format(session?.sessionCalories ?: 0.0)} trimp=${"%.1f".format(session?.sessionTrimp ?: 0.0)}")
            val registeredId = session?.id ?: ""
            val registeredDate = session?.date ?: ""
            _uiState.value = _uiState.value.copy(
                sessionRegistered = true,
                registeredSessionId = registeredId,
                registeredSessionDate = registeredDate,
            )
            _sessionRegisteredEvent.send(registeredId to registeredDate)
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

            // Load the last 12 weeks of history once, then derive both the routine-scoped chart
            // series and the cross-routine kcal/TRIMP/VO2max series from the same list.
            val today = LocalDate.now()
            val startDate = today.with(DayOfWeek.MONDAY).minusWeeks(11)
            val windowSessions = workoutRepository.getSessionsInRange(startDate, today)
            val allSessions = windowSessions
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
            val allCompletedSessions = windowSessions.filter { it.completedAt.isNotBlank() }
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
                    // Any session left without a "Termina" tap is discarded on exit, no matter
                    // how much was logged into it (explicit product decision — see
                    // WorkoutRepository.deleteUnfinalizedSessions, which does the same on the
                    // next launch if onCleared() never ran, e.g. a process kill or crash).
                    if (reloaded.completedAt.isBlank()) {
                        appLogger.w(TAG, "Unfinalized session deleted on exit: id=${session.id}")
                        polarManager.deleteEcgFile(session.id)
                        workoutRepository.delete(reloaded)
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                appLogger.e(TAG, "Session cleanup failed", e)
            } finally {
                clearScope.cancel()
            }
        }
    }
}
