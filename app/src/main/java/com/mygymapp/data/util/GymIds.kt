package com.mygymapp.data.util

import java.util.UUID

/**
 * Unique-ID minting with collision check (Sprint 1).
 *
 * IDs are 8 lowercase hex chars (32 bit): `ex-{8hex}`, `rt-{8hex}`, bare `{8hex}` for
 * sessions/readiness. No call site checked for existence before, so a (rare) collision
 * took the "rename/update" branch of the wrong record and could delete/tombstone the
 * wrong file. These helpers loop until the candidate is unused.
 *
 * Pure (caller supplies [exists]) so it is unit-testable without Context/files.
 */
object GymIds {

    private const val MAX_ATTEMPTS = 50

    fun newSessionId(exists: (String) -> Boolean): String =
        generate(prefix = "", exists = exists)

    fun newExerciseId(exists: (String) -> Boolean): String =
        generate(prefix = "ex-", exists = exists)

    fun newRoutineId(exists: (String) -> Boolean): String =
        generate(prefix = "rt-", exists = exists)

    fun newReadinessId(exists: (String) -> Boolean): String =
        generate(prefix = "", exists = exists)

    private fun generate(prefix: String, exists: (String) -> Boolean): String {
        repeat(MAX_ATTEMPTS) {
            val candidate = prefix + UUID.randomUUID().toString().replace("-", "").take(8)
            if (!exists(candidate)) return candidate
        }
        // Astronomically unlikely (50 consecutive 32-bit collisions); fall back to a
        // longer suffix so the save still succeeds instead of looping forever.
        val fallback = prefix + UUID.randomUUID().toString().replace("-", "").take(16)
        if (!exists(fallback)) return fallback
        throw IllegalStateException("GymIds: cannot mint a unique id with prefix '$prefix'")
    }
}
