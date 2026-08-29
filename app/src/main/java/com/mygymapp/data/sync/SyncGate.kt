package com.mygymapp.data.sync

/**
 * The single rule every `…SyncWorker.doWork()` applies before draining its ledger, and the
 * one thing that decides whether a catalogue edit / measurement kicks an immediate upload.
 *
 * See `docs/SYNC.md` §1.5. Semantics of the "Sincronizzazione attiva" toggle
 * ([SyncConfigRepository.isEnabled]):
 *
 * | trigger                          | toggle OFF        | toggle ON |
 * |----------------------------------|-------------------|-----------|
 * | catalogue edit / readiness / weigh-in / loose ECG | queue only | upload now |
 * | **end of session** (`registerRoutine`)            | **upload all** (force) | upload all |
 * | 4h periodic net                  | **no-op**         | runs |
 * | "Invia dati in coda" / "Verifica backup" | runs (force) | runs |
 *
 * `isConfigured()` (URL + token present) is the hard precondition — nothing ever leaves the
 * phone without it, regardless of the toggle or [force].
 *
 * [force] is carried on the WorkManager `inputData` of the one-off request: `true` only when
 * the run was asked for by an explicit, user-meaningful action (session finalize, a manual
 * "send everything" button). The periodic request never sets it.
 */
fun shouldSyncRun(configured: Boolean, enabled: Boolean, force: Boolean): Boolean =
    configured && (enabled || force)

/** WorkManager inputData key for the [shouldSyncRun] `force` flag. */
const val SYNC_FORCE_KEY = "force"
