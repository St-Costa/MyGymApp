package com.mygymapp.data.sync

/** Delivery state of one queued session in the sync ledger (`gymdata/_sync/state.yml`). */
enum class SyncStatus { PENDING, SENT, FAILED }

/**
 * One row of the local sync ledger — the durable record of "has this exact session
 * content been handed to the server yet." See `docs/SYNC.md` §1.2.
 *
 * [contentHash] is the sha256 of the session file's bytes *at the time this entry was
 * last enqueued*. If the file on disk changes afterward (e.g. a rename-sync rewrite),
 * the hash won't match and the entry is requeued — see [SyncLedgerRepository.requeueIfChanged].
 */
data class SyncLedgerEntry(
    val sessionId: String,
    val relPath: String,
    val status: SyncStatus = SyncStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: String = "",
    val lastError: String = "",
    val contentHash: String = "",
)
