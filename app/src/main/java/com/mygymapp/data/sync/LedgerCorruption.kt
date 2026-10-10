package com.mygymapp.data.sync

import java.io.File

/**
 * Shared corrupt-ledger handling (Sprint 1: i ledger non devono più fare amnesia).
 *
 * All five ledgers used to `catch { return emptyMap() }` on a truncated/unparseable
 * YAML file — every pending entry silently forgotten. Now a file whose top-level shape
 * is wrong is quarantined to `<name>.corrupt-<epochMs>.bak` (best-effort, never throws)
 * so the data is recoverable and the next write starts from a clean slate.
 */
fun quarantineCorruptFile(file: File) {
    runCatching {
        if (!file.exists()) return
        val bak = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}.bak")
        file.renameTo(bak)
        // renameTo can fail silently (e.g. target exists); fall back to a copy+delete so
        // the corrupt bytes are preserved somewhere before we start overwriting.
        if (file.exists() && !bak.exists()) {
            runCatching {
                bak.writeBytes(file.readBytes())
                file.delete()
            }
        }
    }
}
