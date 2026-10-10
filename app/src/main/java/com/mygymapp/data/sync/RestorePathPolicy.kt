package com.mygymapp.data.sync

/**
 * Allowlist for full-store restore writes (Sprint 1: sicurezza restore).
 *
 * `applyRestoredFiles()` previously filtered only `..` and a leading slash: a compromised
 * or buggy server could make the phone overwrite sync ledgers, `logs/`,
 * caches or derived sidecars. Every pulled `relPath` must pass [isAllowedRestorePath]
 * before it is written; rejected paths are counted as failures in the restore report.
 *
 * Pure, no Android — unit-tested in `RestorePathPolicyTest`.
 */
object RestorePathPolicy {

    private val historySession = Regex("""^history/\d{4}/\d{2}/[^/]+\.md$""")
    private val readiness = Regex("""^readiness/[^/]+\.md$""")
    private val scale = Regex("""^scale/\d{4}/\d{2}/[^/]+\.md$""")
    private val exercise = Regex("""^exercises/[^/]+\.md$""")
    private val routine = Regex("""^routines/[^/]+\.md$""")
    private val ecg = Regex("""^ecg/[^/]+\.ecg$""")

    fun isAllowedRestorePath(relPath: String): Boolean {
        if (relPath.isBlank()) return false
        // Traversal / absolute / Windows-absolute / drive-letter / backslash escapes.
        if (relPath.contains("..")) return false
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return false
        if (relPath.contains("\\")) return false
        if (relPath.contains(":") || relPath.any { it.code == 0 }) return false
        // Never let the server touch sync state, logs, caches or derived sidecars.
        val lower = relPath.lowercase()
        if (lower.startsWith("_sync/") || lower.contains("/_sync/")) return false
        if (lower.startsWith("logs/") || lower.contains("/logs/")) return false
        if (lower.startsWith("cache/") || lower.contains("/cache/")) return false
        if (lower.startsWith("image_cache/") || lower.contains("/image_cache/")) return false
        if (lower.startsWith("deleted/") || lower.contains("/deleted/")) return false
        if (lower.contains("/_idx/") || lower.contains("/_stats/")) return false
        if (lower == "history/_gitgraph.yaml" || lower.endsWith("/_gitgraph.yaml")) return false
        if (lower.startsWith("history/_")) return false
        // Hidden files except the well-known profile `_settings.md`.
        val base = relPath.substringAfterLast('/')
        if (base.startsWith(".") && base != ".nomedia") return false

        return historySession.matches(relPath) ||
            readiness.matches(relPath) ||
            scale.matches(relPath) ||
            exercise.matches(relPath) ||
            routine.matches(relPath) ||
            ecg.matches(relPath)
    }
}
