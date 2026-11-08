package com.mygymapp.ui.screen.cardioexercise

import com.mygymapp.data.model.ExerciseSet
import java.time.LocalDateTime

/**
 * Explicit cardio block lifecycle (ISM-19).
 *
 * Before this file existed the block state was spread across the ViewModel's
 * transient fields (`isBlockRunning`, `completedBlocks`, `blockStartedAt`,
 * HR accumulators, jobs) plus a private button enum in the Screen, with the
 * transition rules living in prose (`completeExercise`'s KDoc) and in three
 * near-identical branch nests (`stopBlock`, `completeExercise`,
 * `saveProgressOnExit`). The decisions below are the single definition of
 * each rule — the ViewModel only executes them — so the skip-vs-performed
 * line, the kill-recovery close, and the never-force-close-on-exit guarantee
 * are unit-testable without a timer, a strap, or a process kill.
 * Job/timer/HR-collection mechanics stay in the ViewModel.
 */

/** What the single action button offers. See [cardioButtonState]. */
enum class CardioButtonState { IDLE, RUNNING, DONE }

/**
 * Mirrors the Screen's button derivation: a running block always wins
 * (even with older completed blocks underneath), otherwise any completed
 * block offers COMPLETE, otherwise the lifter hasn't started anything.
 */
fun cardioButtonState(isBlockRunning: Boolean, hasCompletedBlocks: Boolean): CardioButtonState =
    when {
        isBlockRunning -> CardioButtonState.RUNNING
        hasCompletedBlocks -> CardioButtonState.DONE
        else -> CardioButtonState.IDLE
    }

/** Outcome of an explicit Complete tap. See [cardioCompletion]. */
enum class CardioCompletion { PERFORMED, SKIPPED }

/**
 * Mirrors `completeExercise`'s outcome rule: a force-closed running block and
 * a DONE list both close as performed work (a kill-recovered ghost-close
 * counts too — it is a normal block by then); only IDLE-with-nothing closes
 * as skipped, mirroring the stretch `anyDone` guard.
 */
fun cardioCompletion(isRunning: Boolean, hasCompletedBlocks: Boolean): CardioCompletion =
    if (isRunning || hasCompletedBlocks) CardioCompletion.PERFORMED else CardioCompletion.SKIPPED

/**
 * Kill-recovery (VM `init`): a block with a start but no end means the app
 * died mid-block, not a clean "Termina". Resuming it live isn't worth the
 * complexity, so close it with `nowText` — otherwise a normal block.
 * A block with neither timestamp is left alone (nothing to close).
 */
fun closeOpenCardioBlocks(
    blocks: List<ExerciseSet.Cardio>,
    nowText: String,
): List<ExerciseSet.Cardio> = blocks.map { block ->
    if (block.endedAt.isBlank() && block.startedAt.isNotBlank()) block.copy(endedAt = nowText)
    else block
}

/**
 * Mirrors `saveProgressOnExit`: back-navigation never force-closes a running
 * block — it persists the completed ones plus the still-open block
 * (`endedAt` blank, zero HR; the lifter may come back and resume it, and
 * `init`'s recovery handles the case where they never do). Completion flags
 * are preserved by the caller via `withExerciseSetsOnExit`.
 */
fun exitSaveBlocks(
    isRunning: Boolean,
    completedBlocks: List<ExerciseSet.Cardio>,
    blockStartedAt: LocalDateTime?,
    now: LocalDateTime,
): List<ExerciseSet.Cardio> =
    if (isRunning) {
        completedBlocks + ExerciseSet.Cardio(
            startedAt = (blockStartedAt ?: now).toString(),
            endedAt = "",
            avgHr = 0,
            maxHr = 0,
        )
    } else {
        completedBlocks
    }

/** Block-close average: no samples means 0 (never a divide-by-zero). */
fun cardioBlockAverage(hrSum: Long, hrSamples: Int): Int =
    if (hrSamples > 0) (hrSum / hrSamples).toInt() else 0
