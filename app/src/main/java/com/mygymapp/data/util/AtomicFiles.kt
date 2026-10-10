package com.mygymapp.data.util

import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe file writes (Sprint 1: integrità dati).
 *
 * Every previous `File.writeText(...)` in this codebase was a direct overwrite: a
 * kill/process-death mid-write left a truncated YAML file, and every reader's
 * `catch { skip/null }` then silently dropped the record. These helpers implement the
 * standard tmp-file + fsync + atomic-rename commit instead:
 *
 * 1. `parent.mkdirs()`, write full content to `<name>.tmp-<nanoTime>` in the SAME
 *    directory (same filesystem, so the rename below is atomic on ext4/f2fs),
 * 2. `FileDescriptor.sync()` to force bytes to storage before the rename is visible,
 * 3. `renameTo(target)` (with delete+rename fallback where `renameTo` won't overwrite).
 *
 * Callers must already be on `Dispatchers.IO` (all repository save paths are). Never
 * throws for the quarantine path — see [com.mygymapp.data.sync.quarantineCorruptFile].
 * The write helpers DO throw [java.io.IOException] on failure so a failed save is loud
 * instead of silently half-persisted.
 */
object AtomicFiles {

    fun writeTextAtomically(file: File, text: String) {
        writeBytesAtomically(file, text.toByteArray(Charsets.UTF_8))
    }

    fun writeBytesAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw java.io.IOException("No parent for ${file.path}")
        parent.mkdirs()
        val tmp = File(parent, "${file.name}.tmp-${System.nanoTime()}-${Thread.currentThread().id}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            commitTmp(tmp, file)
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            if (e is java.io.IOException) throw e
            throw java.io.IOException("Atomic write failed for ${file.path}: ${e.message}", e)
        }
    }

    private fun commitTmp(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        // renameTo() won't overwrite on some filesystems — delete-then-rename.
        // The window where `target` is missing is bounded by two metadata ops in the
        // same directory; a crash there loses the file but never leaves it truncated,
        // and every reader already treats "missing" as a clean skip.
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw java.io.IOException("Atomic write: cannot replace ${target.path}")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("Atomic write: rename failed for ${target.path}")
        }
    }
}
