package com.mygymapp.data.sync

/**
 * Delivery state of one queued session in the sync ledger (`gymdata/_sync/state.yml`).
 *
 * [EXPIRED] is produced by [EcgSyncLedgerRepository.expireStale] (30-day age cap on the
 * ephemeral raw ECG) and by every pipeline's `markDead()` for entries whose source file
 * is gone for good (hand-deleted or reaped): a terminal "gave up" state distinct from
 * `FAILED` (still retryable), excluded from every `getPending()`.
 *
 * [DELETED_PENDING] / [DELETED_SENT] are only ever produced by [RepoLedgerRepository] — the
 * fifth pipeline (exercises/routines, see `docs/BACKUP.md`) is the only one that syncs
 * *deletions*, since sessions/readiness/scale/ecg files are never removed on the phone. A
 * `DELETED_PENDING` entry has no file on disk (it's gone) — the worker POSTs `op: "delete"`
 * with the last-known content hash and flips it to `DELETED_SENT` on a 2xx.
 */
enum class SyncStatus { PENDING, SENT, FAILED, EXPIRED, DELETED_PENDING, DELETED_SENT }

/**
 * Explicit transition table for every sync ledger (ISM-05).
 *
 * Before this existed each repository's `markSent`/`markFailed`/`markDead` accepted
 * any source state — the legal moves lived only in prose (`SyncLedgerEntry` KDoc,
 * `docs/SYNC.md`). The outcome functions now refuse (no-op, no write) anything
 * outside this table, so an illegal move can no longer silently corrupt delivery
 * state (e.g. un-sending a SENT entry, or resurrecting an EXPIRED dead-letter
 * into the retry loop).
 *
 * Audited against every live caller (2026-11-08): workers only ever move
 * PENDING/FAILED entries to SENT/FAILED/EXPIRED; `enqueue`/`requeueIfChanged`
 * (fresh hash wins), `markRestored` (post-restore) and `markDeleted` (tombstone)
 * are sanctioned resets and stay outside this table on purpose — they carry new
 * ground truth (fresh bytes, server bytes, user delete), not a worker outcome.
 * `resetAllToPending` preserves EXPIRED for the same reason: a dead-letter is
 * revived only by an explicit re-enqueue, never by a bulk reset.
 */
fun SyncStatus.canTransitionTo(target: SyncStatus): Boolean = when (this) {
    // A queued entry resolves to sent, failed (retryable), or dead-lettered.
    SyncStatus.PENDING -> target == SyncStatus.SENT ||
        target == SyncStatus.FAILED ||
        target == SyncStatus.EXPIRED
    // A retry resolves the same way; FAILED→FAILED only counts the new attempt.
    SyncStatus.FAILED -> target == SyncStatus.SENT ||
        target == SyncStatus.FAILED ||
        target == SyncStatus.EXPIRED
    // A sent delivery is never un-sent, failed retroactively, or dead-lettered by
    // a worker outcome. The only way out is an explicit re-enqueue with a fresh
    // hash (`enqueue`/`requeueIfChanged`) when the content actually changed.
    SyncStatus.SENT -> false
    // Terminal by design: revival needs a fresh enqueue with a live file, so a
    // bulk/reset path can never drag it back into the 4h retry loop.
    SyncStatus.EXPIRED -> false
    // A delete tombstone resolves to delivered, or stays a tombstone counting
    // the failed attempt. It never becomes a plain upsert status.
    SyncStatus.DELETED_PENDING -> target == SyncStatus.DELETED_SENT ||
        target == SyncStatus.DELETED_PENDING
    // Delivered tombstone: only a file reappearing (`requeueIfChanged`
    // un-tombstones) or a fresh delete (`markDeleted` is a no-op here by
    // idempotence guard) touches it — never a worker outcome.
    SyncStatus.DELETED_SENT -> false
}

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

/**
 * [RepoLedgerRepository]'s row shape — the fifth pipeline (exercises + routines, see
 * `docs/BACKUP.md`). Keyed by **relative path** (`exercises/{slug}-{id}.md`), not an id,
 * because the slug embedded in the filename changes on rename.
 *
 * Differs from [SyncLedgerEntry]:
 * - [op] — `"upsert"` or `"delete"`. A `"delete"` entry has no file on disk; the worker
 *   sends `op: "delete"` with [contentHash] = the last-known content, and the entry moves
 *   to [SyncStatus.DELETED_SENT] on success.
 * - No `bytesSent`/`durationMs`/`serverStatus` — the repo pipeline has no end-of-session
 *   summary box to surface them on.
 */
data class RepoLedgerEntry(
    val relPath: String,
    val op: String = "upsert",
    val status: SyncStatus = SyncStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAt: String = "",
    val lastError: String = "",
    val contentHash: String = "",
)
