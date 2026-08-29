package com.mygymapp.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Sincronizzazione attiva" toggle semantics, docs/SYNC.md §1.5. One rule, all five
 * sync workers: a run drains its ledger iff the server is configured AND (the toggle is on
 * OR this run was explicitly forced — session finalize / a manual "send all" button).
 */
class SyncGateTest {

    @Test
    fun `nothing runs without a configured server`() {
        assertFalse(shouldSyncRun(configured = false, enabled = false, force = false))
        assertFalse(shouldSyncRun(configured = false, enabled = true, force = false))
        assertFalse(shouldSyncRun(configured = false, enabled = false, force = true))
        assertFalse("force cannot override a missing server URL/token",
            shouldSyncRun(configured = false, enabled = true, force = true))
    }

    @Test
    fun `toggle on runs regardless of force`() {
        assertTrue(shouldSyncRun(configured = true, enabled = true, force = false))
        assertTrue(shouldSyncRun(configured = true, enabled = true, force = true))
    }

    @Test
    fun `toggle off runs only when forced`() {
        assertFalse("periodic net / edit nudge is a no-op while the toggle is off",
            shouldSyncRun(configured = true, enabled = false, force = false))
        assertTrue("end-of-session / manual send forces past the toggle",
            shouldSyncRun(configured = true, enabled = false, force = true))
    }
}
