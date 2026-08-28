package com.mygymapp.data.sync

/**
 * Delivery state of one queued session in the sync ledger (`gymdata/_sync/state.yml`).
 *
 * [EXPIRED] is only ever produced by [EcgSyncLedgerRepository.expireStale] — the raw ECG
 * pipeline is the only one whose source file is ephemeral, so it's the only one that needs
 * a terminal "gave up" state distinct from `FAILED` (still retryable). The other three
 * pipelines (sessions/readiness/scale) never assign this value.
 */
enum class SyncStatus { PENDING, SENT, FAILED, EXPIRED }

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
    // Populated on the successful attempt only (see [SyncLedgerRepository.markSent]) and
    // surfaced on the end-of-session summary box: byte count of the file uploaded, wall
    // time of that HTTP call, and the server's own receipt word (`stored` / `duplicate`).
    // Only the session ledger writes/reads these — the readiness/scale ledgers reuse this
    // type but simply never persist them (0 / "" round-trips fine).
    val bytesSent: Long = 0,
    val durationMs: Long = 0,
    val serverStatus: String = "",
)

/**
 * [EcgSyncLedgerRepository]'s row shape — same fields as [SyncLedgerEntry] plus
 * [enqueuedAt], needed for [EcgSyncLedgerRepository.expireStale]'s 30-day age cap. Kept as
 * a separate type rather than adding a nullable field to [SyncLedgerEntry] so the other
 * three ledgers (which have no expiry concept) aren't forced to carry an unused field.
 */
data class EcgSyncLedgerEntry(
    val sessionId: String,
    val relPath: String,
    val status: SyncStatus = SyncStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: String = "",
    val lastError: String = "",
    val contentHash: String = "",
    val enqueuedAt: String = "",
)
