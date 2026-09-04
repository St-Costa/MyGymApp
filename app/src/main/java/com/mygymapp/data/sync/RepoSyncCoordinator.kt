package com.mygymapp.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes repo uploads that can be started by either WorkManager or BackupVerifier.
 * Both paths touch the same ledger and server-side repo; allowing them to overlap creates
 * duplicate uploads and duplicate git commits.
 */
object RepoSyncCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
