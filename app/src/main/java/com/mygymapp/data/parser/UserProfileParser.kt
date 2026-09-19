package com.mygymapp.data.parser

import com.mygymapp.data.polar.UserProfile

/**
 * Serializes the non-secret user profile to `exercises/_settings.md` — the file-backed
 * source of truth owned by `UserProfileRepository` (see `docs/BACKUP.md` §6).
 *
 * Pure JVM logic (no `Context`/I/O): covered by `UserProfileParserTest`.
 */
object UserProfileParser {

    fun toMarkdown(profile: UserProfile): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "type" to "settings",
            "version" to 1,
            "weightKg" to profile.weightKg,
            "isMale" to profile.isMale,
            "heightCm" to profile.heightCm,
        )
        // Absent until set — same "missing key means unset" convention the old
        // `user_profile` SharedPreferences used (`remove("birthYear")`).
        if (profile.birthYear != null) frontmatter["birthYear"] = profile.birthYear
        return MarkdownParser.serialize(frontmatter, "")
    }

    /**
     * Returns `null` when [content] has no usable frontmatter (missing/empty/corrupt
     * file) so the caller can fall back to the legacy prefs, then defaults. Lenient
     * per-key: a single bad value falls back to that field's default rather than
     * discarding the whole profile.
     */
    fun fromMarkdown(content: String): UserProfile? {
        val doc = runCatching { MarkdownParser.parse(content) }.getOrNull() ?: return null
        if (doc.frontmatter.isEmpty()) return null
        val fm = doc.frontmatter
        return UserProfile(
            weightKg = fm["weightKg"]?.toString()?.toDoubleOrNull() ?: 75.0,
            isMale = fm["isMale"]?.toString()?.toBooleanStrictOrNull() ?: true,
            heightCm = fm["heightCm"]?.toString()?.toIntOrNull() ?: 175,
            birthYear = fm["birthYear"]?.toString()?.toIntOrNull()?.takeIf { it > 0 },
        )
    }
}
