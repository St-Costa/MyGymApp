package com.mygymapp.data.repository

import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.model.isFinalizedOnDisk
import com.mygymapp.data.model.isGhost
import com.mygymapp.data.model.lifecycle
import java.time.LocalDate

/** Why a session file is being deleted by maintenance (vs kept). */
enum class MaintenanceDeleteKind { GHOST, PRUNED }

/**
 * Explicit per-file maintenance decision (ISM-29).
 *
 * Before this type existed the fate of each `history/` file was decided by three
 * inline booleans in `WorkoutRepository.runMaintenance` (unparseable filename
 * date → discard; blank-`completedAt` → ghost; older-than-cutoff → prune) plus
 * a separately recomputed `affectsStats` flag (`completedAt` non-blank) gating
 * the stats-sidecar rebuild. The legal combinations lived only in the loop, so
 * a reader couldn't tell "ghost ⇒ never affects stats" was structural rather
 * than coincidental. [decideMaintenanceAction] is the single definition; the
 * loop is now a `when` over it, and the sidecar rule falls out of the
 * disk-side lifecycle (`save()` only ever merges finalized sessions, so a
 * ghost — [com.mygymapp.data.model.SessionLifecycle.Ghost] — can never have
 * fed one).
 */
sealed interface MaintenanceAction {
    /** Leave the file alone; its id feeds the orphan-ECG survivor set. */
    data object Keep : MaintenanceAction

    /**
     * Filename date unparseable: discard outright. Removes nothing from the
     * exercise index and touches no counter or sidecar — there is no usable
     * identity to invalidate by.
     */
    data object DeleteMalformedName : MaintenanceAction

    /**
     * Delete the file and drop [exerciseIds] from the exercise index.
     * [affectsStats] is true only for a *completed* pruned session: only it can
     * have fed an exercise's `_stats/` sidecar (PR / previous), so only it
     * schedules a rebuild. Ghosts carry `false` by construction.
     */
    data class Delete(
        val kind: MaintenanceDeleteKind,
        val exerciseIds: Set<String>,
        val affectsStats: Boolean,
    ) : MaintenanceAction
}

/**
 * Pure per-file verdict for [WorkoutRepository.runMaintenance]: one parse in,
 * one decision out. Matches the historical behavior exactly (see
 * `MaintenanceAction`): malformed name always discarded; ghosts always
 * deleted whatever their date; otherwise pruned only when older than
 * [cutoffDate]; an unparseable *content* with a fresh date is kept (left for
 * readers' `catch { skip }`, same as before).
 */
fun decideMaintenanceAction(
    fileDate: LocalDate?,
    session: WorkoutSession?,
    cutoffDate: LocalDate,
): MaintenanceAction {
    if (fileDate == null) return MaintenanceAction.DeleteMalformedName
    if (session != null && session.lifecycle().isGhost) {
        return MaintenanceAction.Delete(
            kind = MaintenanceDeleteKind.GHOST,
            exerciseIds = session.exercises.map { it.exerciseId }.toSet(),
            affectsStats = false,
        )
    }
    if (fileDate.isBefore(cutoffDate)) {
        val affectsStats = session?.lifecycle()?.isFinalizedOnDisk == true
        return MaintenanceAction.Delete(
            kind = MaintenanceDeleteKind.PRUNED,
            exerciseIds = session?.exercises?.map { it.exerciseId }?.toSet().orEmpty(),
            affectsStats = affectsStats,
        )
    }
    return MaintenanceAction.Keep
}
