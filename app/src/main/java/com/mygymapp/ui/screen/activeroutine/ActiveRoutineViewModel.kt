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
import com.mygymapp.data.polar.PolarManager
import com.mygymapp.data.repository.ExerciseRepository
import com.mygymapp.data.repository.RoutineRepository
import com.mygymapp.data.repository.WorkoutRepository
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val sessionTonnageByBodypart: Map<String, List<Double>> = emptyMap(),
    val sessionLabels: List<String> = emptyList(),
    val selectedChartFilter: String = "Totale",
    val isLoadingChart: Boolean = false,
    val sessionRegistered: Boolean = false,
    // Cross-routine charts (all sessions, not filtered by routine)
    val allSessionCalories: List<Double> = emptyList(),
    val allSessionTrimp: List<Double> = emptyList(),
    val allSessionVo2max: List<Double> = emptyList(),
    val allSessionLabels: List<String> = emptyList(),
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
    val supersetWithNext: Boolean = false,
    val excludeFromTonnage: Boolean = false,
    val category: SessionExerciseCategory = SessionExerciseCategory.NORMAL,
)

@HiltViewModel
class ActiveRoutineViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    private val polarManager: PolarManager,
) : ViewModel() {

    private val routineId: String = savedStateHandle["routineId"] ?: ""

    private val _uiState = MutableStateFlow(ActiveRoutineUiState())
    val uiState: StateFlow<ActiveRoutineUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var previousTonnageByExercise: Map<String, Double> = emptyMap()
    private var sessionFinalized = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            val routine = routineRepository.getById(routineId) ?: return@launch
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

            // Create and save the workout session
            val workoutExercises = ordered.mapNotNull { (re, category) ->
                val exercise = exerciseRepository.getById(re.exerciseId) ?: return@mapNotNull null
                val sets = (1..re.sets).map { _ ->
                    when (exercise.type) {
                        ExerciseType.FORZA -> ExerciseSet.Strength()
                        ExerciseType.STRETCH -> ExerciseSet.Stretch(timeSeconds = re.timePerSetSeconds)
                    }
                }
                WorkoutExercise(
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    bodypart = exercise.bodypart,
                    type = exercise.type,
                    sets = sets,
                    excludeFromTonnage = category != SessionExerciseCategory.NORMAL,
                )
            }

            val session = WorkoutSession(
                id = "",
                routineId = routineId,
                routineName = routine.name,
                date = LocalDate.now().toString(),
                exercises = workoutExercises,
                notes = routine.notes,
            )
            val saved = workoutRepository.save(session)
            currentSession = saved

            // Start ECG recording + HR series capture (no-op if Polar not connected)
            polarManager.startEcgRecording(saved.id)
            polarManager.startHrSeriesCapture()

            _uiState.value = ActiveRoutineUiState(
                routineName = routine.name,
                notes = routine.notes,
                exercises = exercises,
                sessionId = saved.id,
                isLoading = false,
                previousTonnage = previousSession?.totalTonnage,
            )
        }
    }

    fun markExerciseCompleted(exerciseId: String) {
        viewModelScope.launch {
            // Reload session from disk to get actual set data written by exercise screen
            val session = currentSession ?: return@launch
            val today = LocalDate.parse(session.date)
            val reloaded = workoutRepository.getSession(session.id, today) ?: session

            // Compute current tonnage for this exercise
            val currentExTonnage = reloaded.exercises
                .find { it.exerciseId == exerciseId }
                ?.sets?.filterIsInstance<ExerciseSet.Strength>()
                ?.sumOf { it.reps * it.weight } ?: 0.0

            val prevExTonnage = previousTonnageByExercise[exerciseId]
            val changePct: Double? = if (prevExTonnage != null && prevExTonnage > 0) {
                ((currentExTonnage - prevExTonnage) / prevExTonnage) * 100.0
            } else null

            val updatedExercises = _uiState.value.exercises.map { ex ->
                if (ex.exerciseId == exerciseId) {
                    ex.copy(
                        completed = true,
                        // No tonnage comparison for warmup/fixed-daily exercises.
                        tonnageChangePct = if (ex.type == ExerciseType.FORZA && !ex.excludeFromTonnage) changePct else null,
                    )
                } else ex
            }
            val allCompleted = updatedExercises.all { it.completed }
            _uiState.value = _uiState.value.copy(
                exercises = updatedExercises,
                allCompleted = allCompleted,
            )

            if (allCompleted) {
                finalizeSession(reloaded)
            }
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

    fun selectChartFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedChartFilter = filter)
    }

    fun registerRoutine() {
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
                // Heavy analysis (Pan-Tompkins on the whole file) runs on IO and is
                // guarded — a failure here must NOT crash the register flow.
                val ecgFileSize = polarManager.ecgFileSize(session.id)
                Log.i("ActiveRoutineVM", "ECG file for ${session.id}: $ecgFileSize bytes")
                val ecgResult = try {
                    withContext(Dispatchers.IO) { polarManager.analyzeSessionEcg(session.id) }
                } catch (e: Throwable) {
                    Log.e("ActiveRoutineVM", "ECG analysis failed: ${e.message}", e)
                    null
                }
                Log.i("ActiveRoutineVM", "ECG analysis result: ecgResult=$ecgResult")
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
                if (ecgResult != null && ecgResult.hasAnything) {
                    updated = updated.copy(
                        ecgBeats = ecgResult.beatsDetected,
                        ecgDurationSec = ecgResult.durationSeconds,
                        ecgAvgHr = ecgResult.avgHr,
                        ecgSessionRmssd = ecgResult.sessionRmssd,
                        ecgPacCount = ecgResult.pacCount,
                        ecgPauseCount = ecgResult.pauseCount,
                        ecgIrregularBeats = ecgResult.irregularBeats,
                        sdnn = ecgResult.sdnn,
                        pnn50 = ecgResult.pnn50,
                        poincareSd1 = ecgResult.poincareSd1,
                        poincareSd2 = ecgResult.poincareSd2,
                        poincareRatio = ecgResult.poincareRatio,
                        afibSuspicionEpisodes = ecgResult.afibSuspicionEpisodes,
                    )
                }
                try {
                    workoutRepository.save(updated)
                    currentSession = updated
                } catch (e: Throwable) {
                    Log.e("ActiveRoutineVM", "Save session failed", e)
                }
                // Only delete the raw ECG file when analysis succeeded. If it failed,
                // keep the file so the session can be re-analyzed or inspected offline.
                if (ecgResult != null && ecgResult.hasAnything) {
                    try { polarManager.deleteEcgFile(session.id) } catch (_: Throwable) {}
                } else {
                    Log.w("ActiveRoutineVM", "Keeping ECG file for ${session.id}: analysis produced no metrics")
                }
            }
            _uiState.value = _uiState.value.copy(sessionRegistered = true)
        }
    }

    fun abandonSession() {
        viewModelScope.launch {
            val session = currentSession ?: return@launch
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
        clearScope.launch {
            try {
                polarManager.stopEcgRecording()
                polarManager.stopHrSeriesCapture()
                if (needsGhostCleanup) {
                    val today = LocalDate.parse(session.date)
                    val reloaded = workoutRepository.getSession(session.id, today) ?: session
                    val hasCompleted = reloaded.exercises.any { it.completed }
                    val hasRealSetData = reloaded.exercises.any { ex ->
                        ex.sets.any { set ->
                            when (set) {
                                is ExerciseSet.Strength -> set.reps > 0 || set.weight > 0.0
                                is ExerciseSet.Stretch -> set.done
                            }
                        }
                    }
                    if (reloaded.completedAt.isBlank() && !hasCompleted && !hasRealSetData) {
                        polarManager.deleteEcgFile(session.id)
                        workoutRepository.delete(reloaded)
                    }
                }
            } catch (e: Throwable) {
                Log.e("ActiveRoutineVM", "Session cleanup failed", e)
            } finally {
                clearScope.cancel()
            }
        }
    }
}
