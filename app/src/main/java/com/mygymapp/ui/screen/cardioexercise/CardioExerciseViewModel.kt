package com.mygymapp.ui.screen.cardioexercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.model.Exercise
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.FIXED_DAILY_ROUTINE_ID
import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.polar.PolarManager
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
import java.time.LocalDateTime
import javax.inject.Inject

/** One past cardio session for this exercise, for the history panel. */
data class CardioHistoryEntry(
    val date: String,
    val durationSeconds: Int,
    val avgHr: Int,
    val maxHr: Int,
)

data class CardioExerciseUiState(
    val exercise: Exercise? = null,
    val isLoading: Boolean = true,
    val completedBlocks: List<ExerciseSet.Cardio> = emptyList(),
    val isBlockRunning: Boolean = false,
    // Configured block duration (RoutineExercise.timePerSetSeconds for CARDIO — see
    // RoutineEditScreen), 0 if unset/not found. Drives the countdown below.
    val configuredDurationSeconds: Int = 0,
    // Seconds remaining in the countdown — counts down from configuredDurationSeconds and
    // keeps going negative (overtime) past zero rather than auto-stopping; the user must tap
    // "Termina cardio" explicitly (see stopBlock()).
    val remainingSeconds: Int = 0,
    val liveHr: Int? = null,
    val history: List<CardioHistoryEntry> = emptyList(),
)

/**
 * Drives one cardio exercise screen: "Inizia cardio" / "Termina cardio" for one or more
 * continuous blocks (see docs/STORAGE.md — ExerciseSet.Cardio), each with on-device HR
 * avg/max computed from PolarManager.heartRate while the block runs. Mirrors
 * StretchExerciseViewModel's session read/save + completionSaved pattern; see
 * docs/CONVENTIONS.md#completionsaved-pattern.
 */
@HiltViewModel
class CardioExerciseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository,
    private val routineRepository: RoutineRepository,
    private val polarManager: PolarManager,
) : ViewModel() {

    private val sessionId: String = savedStateHandle["sessionId"] ?: ""
    private val exerciseId: String = savedStateHandle["exerciseId"] ?: ""

    private val _uiState = MutableStateFlow(CardioExerciseUiState())
    val uiState: StateFlow<CardioExerciseUiState> = _uiState

    private var currentSession: WorkoutSession? = null
    private var exerciseCompleted = false
    private val clearScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Live-block bookkeeping — reset every time a new block starts, not persisted until
    // "Termina cardio" (or completeExercise() force-closes it).
    private var blockStartedAt: LocalDateTime? = null
    private var hrSum = 0L
    private var hrSamples = 0
    private var hrMax = 0
    private var timerJob: Job? = null
    private var hrCollectJob: Job? = null

    private val _completionSaved = MutableStateFlow(false)
    val completionSaved: StateFlow<Boolean> = _completionSaved

    init {
        viewModelScope.launch {
            val exercise = exerciseRepository.getById(exerciseId) ?: return@launch

            val sessions = workoutRepository.getSessionsInRange(
                java.time.LocalDate.now(), java.time.LocalDate.now()
            )
            val session = sessions.find { it.id == sessionId }
            currentSession = session

            val workoutExercise = session?.exercises?.find { it.exerciseId == exerciseId }
            // A block left with a blank endedAt here means the app died mid-block (e.g. process
            // kill) rather than a clean "Termina cardio" — resuming it live is not worth the
            // complexity, so close it silently with whatever partial HR data was captured
            // (0 if none), same "don't leave inconsistent state lying around" spirit as the
            // ghost-session guard elsewhere. A block closed this way is otherwise a normal one.
            val existingSets = workoutExercise?.sets?.filterIsInstance<ExerciseSet.Cardio>() ?: emptyList()
            val closedBlocks = existingSets.map { block ->
                if (block.endedAt.isBlank() && block.startedAt.isNotBlank()) {
                    block.copy(endedAt = LocalDateTime.now().toString())
                } else block
            }
            if (closedBlocks != existingSets && session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) ex.copy(sets = closedBlocks) else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }

            // Configured duration lives on the routine, not the session — same lookup pattern
            // as SupersetViewModel's rep-range fetch, including the fixed-daily special case.
            val isDaily = workoutExercise?.isDaily ?: false
            val routineId = if (isDaily) FIXED_DAILY_ROUTINE_ID else (session?.routineId ?: "")
            val configuredDuration = if (routineId.isNotBlank()) {
                routineRepository.getById(routineId)?.exercises
                    ?.find { it.exerciseId == exerciseId }
                    ?.timePerSetSeconds ?: 0
            } else 0

            val history = workoutRepository.getSessionsForExercise(exerciseId)
                .filter { it.id != sessionId }
                .mapNotNull { hist ->
                    val blocks = hist.exercises
                        .find { it.exerciseId == exerciseId }
                        ?.sets?.filterIsInstance<ExerciseSet.Cardio>()
                        ?.filter { it.startedAt.isNotBlank() && it.endedAt.isNotBlank() }
                        ?: return@mapNotNull null
                    if (blocks.isEmpty()) return@mapNotNull null
                    val totalSeconds = blocks.sumOf { blockDurationSeconds(it) }
                    val avgHr = blocks.filter { it.avgHr > 0 }.map { it.avgHr }
                        .takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
                    val maxHr = blocks.maxOfOrNull { it.maxHr } ?: 0
                    CardioHistoryEntry(
                        date = hist.date,
                        durationSeconds = totalSeconds,
                        avgHr = avgHr,
                        maxHr = maxHr,
                    )
                }

            _uiState.value = CardioExerciseUiState(
                exercise = exercise,
                isLoading = false,
                completedBlocks = closedBlocks,
                configuredDurationSeconds = configuredDuration,
                remainingSeconds = configuredDuration,
                history = history,
            )
        }
    }

    private fun blockDurationSeconds(block: ExerciseSet.Cardio): Int {
        val start = runCatching { LocalDateTime.parse(block.startedAt) }.getOrNull() ?: return 0
        val end = runCatching { LocalDateTime.parse(block.endedAt) }.getOrNull() ?: return 0
        return java.time.Duration.between(start, end).seconds.toInt().coerceAtLeast(0)
    }

    fun startBlock() {
        if (_uiState.value.isBlockRunning) return
        blockStartedAt = LocalDateTime.now()
        hrSum = 0
        hrSamples = 0
        hrMax = 0
        _uiState.value = _uiState.value.copy(
            isBlockRunning = true,
            remainingSeconds = _uiState.value.configuredDurationSeconds,
            liveHr = null,
        )

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                // Keeps decrementing past zero (overtime) rather than stopping — the user must
                // tap "Termina cardio" explicitly, per the countdown design.
                _uiState.value = _uiState.value.copy(remainingSeconds = _uiState.value.remainingSeconds - 1)
            }
        }
        hrCollectJob?.cancel()
        hrCollectJob = viewModelScope.launch {
            polarManager.heartRate.collect { hr ->
                if (hr != null && hr > 0) {
                    hrSum += hr
                    hrSamples += 1
                    if (hr > hrMax) hrMax = hr
                    _uiState.value = _uiState.value.copy(liveHr = hr)
                }
            }
        }
    }

    fun stopBlock() {
        if (!_uiState.value.isBlockRunning) return
        val block = closeCurrentBlock()
        timerJob?.cancel()
        timerJob = null
        hrCollectJob?.cancel()
        hrCollectJob = null

        val updatedBlocks = _uiState.value.completedBlocks + block
        _uiState.value = _uiState.value.copy(
            isBlockRunning = false,
            remainingSeconds = _uiState.value.configuredDurationSeconds,
            liveHr = null,
            completedBlocks = updatedBlocks,
        )

        viewModelScope.launch {
            val session = currentSession ?: return@launch
            val exercises = session.exercises.map { ex ->
                if (ex.exerciseId == exerciseId) ex.copy(sets = updatedBlocks) else ex
            }
            workoutRepository.save(session.copy(exercises = exercises))
        }
    }

    private fun closeCurrentBlock(): ExerciseSet.Cardio {
        val start = blockStartedAt ?: LocalDateTime.now()
        val avg = if (hrSamples > 0) (hrSum / hrSamples).toInt() else 0
        blockStartedAt = null
        return ExerciseSet.Cardio(
            startedAt = start.toString(),
            endedAt = LocalDateTime.now().toString(),
            avgHr = avg,
            maxHr = hrMax,
        )
    }

    /**
     * Called when the user taps "Complete Exercise". A block left running (user didn't tap
     * "Termina cardio" first) is force-closed here — an ExerciseSet.Cardio with a blank endedAt
     * is never persisted for an exercise marked completed.
     */
    fun completeExercise() {
        exerciseCompleted = true
        val running = _uiState.value.isBlockRunning
        val finalBlocks = if (running) {
            timerJob?.cancel()
            hrCollectJob?.cancel()
            _uiState.value.completedBlocks + closeCurrentBlock()
        } else {
            _uiState.value.completedBlocks
        }
        // No blocks at all means the lifter never touched "Inizia cardio" — treat like an
        // unopened exercise rather than recording empty cardio as performed work, same
        // reasoning as StretchExerciseViewModel's anyDone check.
        val anyBlock = finalBlocks.isNotEmpty()
        viewModelScope.launch {
            val session = currentSession
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        if (anyBlock) {
                            ex.copy(completed = true, completedEmpty = false, sets = finalBlocks)
                        } else {
                            ex.copy(completed = true, completedEmpty = true, sets = emptyList())
                        }
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            _completionSaved.value = true
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        hrCollectJob?.cancel()
        if (exerciseCompleted) {
            clearScope.cancel()
            return
        }
        // Leaving mid-block: persist completed blocks so far, but do NOT force-close a running
        // block — the user may come back and resume it (see init{}'s closedBlocks handling for
        // the case where they never do and the process dies instead).
        val running = _uiState.value.isBlockRunning
        val blocksToSave = if (running) {
            _uiState.value.completedBlocks + ExerciseSet.Cardio(
                startedAt = (blockStartedAt ?: LocalDateTime.now()).toString(),
                endedAt = "",
                avgHr = 0,
                maxHr = 0,
            )
        } else {
            _uiState.value.completedBlocks
        }
        val session = currentSession
        clearScope.launch {
            if (session != null) {
                val exercises = session.exercises.map { ex ->
                    if (ex.exerciseId == exerciseId) {
                        ex.copy(completed = false, sets = blocksToSave)
                    } else ex
                }
                workoutRepository.save(session.copy(exercises = exercises))
            }
            clearScope.cancel()
        }
    }
}
