package com.mygymapp.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit ledger transition table (ISM-05):
 * [SyncStatus.canTransitionTo]. Every live caller was audited against this
 * table (2026-11-08) — workers only move PENDING/FAILED entries to
 * SENT/FAILED/EXPIRED — so the guards in the repositories' `mark*` functions
 * are no-ops on all reachable paths and only refuse the previously-silent
 * corruptions (un-sending SENT, resurrecting EXPIRED).
 */
class SyncTransitionsTest {

    @Test
    fun `PENDING resolves to sent, failed, or dead`() {
        assertTrue(SyncStatus.PENDING.canTransitionTo(SyncStatus.SENT))
        assertTrue(SyncStatus.PENDING.canTransitionTo(SyncStatus.FAILED))
        assertTrue(SyncStatus.PENDING.canTransitionTo(SyncStatus.EXPIRED))
    }

    @Test
    fun `PENDING never self-loops or enters delete states via mark`() {
        assertFalse(SyncStatus.PENDING.canTransitionTo(SyncStatus.PENDING))
        assertFalse(SyncStatus.PENDING.canTransitionTo(SyncStatus.DELETED_PENDING))
        assertFalse(SyncStatus.PENDING.canTransitionTo(SyncStatus.DELETED_SENT))
    }

    @Test
    fun `FAILED retries resolve the same way, including counting the attempt`() {
        assertTrue(SyncStatus.FAILED.canTransitionTo(SyncStatus.SENT))
        assertTrue(SyncStatus.FAILED.canTransitionTo(SyncStatus.FAILED))
        assertTrue(SyncStatus.FAILED.canTransitionTo(SyncStatus.EXPIRED))
        assertFalse(SyncStatus.FAILED.canTransitionTo(SyncStatus.PENDING))
    }

    @Test
    fun `SENT is terminal for worker outcomes`() {
        // The only way out of SENT is an explicit re-enqueue with a fresh hash
        // (enqueue/requeueIfChanged), never markSent/markFailed/markDead.
        for (target in SyncStatus.entries) {
            assertFalse(
                "SENT -> $target must be refused",
                SyncStatus.SENT.canTransitionTo(target),
            )
        }
    }

    @Test
    fun `EXPIRED is terminal for worker outcomes`() {
        // Revival needs a fresh enqueue with a live file — a bulk reset or a
        // worker outcome must never drag it back into the retry loop.
        for (target in SyncStatus.entries) {
            assertFalse(
                "EXPIRED -> $target must be refused",
                SyncStatus.EXPIRED.canTransitionTo(target),
            )
        }
    }

    @Test
    fun `delete tombstone resolves to delivered or stays a tombstone`() {
        assertTrue(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.DELETED_SENT))
        assertTrue(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.DELETED_PENDING))
        assertFalse(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.SENT))
        assertFalse(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.FAILED))
        assertFalse(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.EXPIRED))
        assertFalse(SyncStatus.DELETED_PENDING.canTransitionTo(SyncStatus.PENDING))
    }

    @Test
    fun `delivered tombstone is terminal for worker outcomes`() {
        for (target in SyncStatus.entries) {
            assertFalse(
                "DELETED_SENT -> $target must be refused",
                SyncStatus.DELETED_SENT.canTransitionTo(target),
            )
        }
    }
}
