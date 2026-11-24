package com.mygymapp.data.parser

/**
 * Serializes the paired-device mirror to `exercises/_devices.md` — the file-backed copy of
 * the Polar H10 device ID and the VitaFit scale BLE MAC owned by
 * `KnownDeviceBackupRepository` (see `docs/BACKUP.md` §6, `docs/STORAGE.md`).
 *
 * Why a file and not just `SharedPreferences`: `known_polar_device` / `known_scale` are
 * wiped by every uninstall/`pm clear`/baseline-profile run and never reach any backup, so
 * every reinstall meant re-pairing both devices. A file under `gymdata/` rides the tar
 * backup (`scripts/install-debug.sh`) and the server full-store backup like everything
 * else, and the `Known*` repositories self-heal their prefs from it when empty.
 * Same `_`-prefix convention as `_settings.md`: skipped by `ExerciseRepository` and by
 * the server SQL view — never synced as an exercise.
 *
 * Neither value is a secret (the Polar ID is printed on the sensor, the MAC is visible in
 * any BLE scan), so unlike the sync bearer / server URL they MAY be backed up to the
 * server they authenticate against.
 *
 * Pure JVM logic (no `Context`/I/O): covered by `DeviceBackupParserTest`.
 */
data class DeviceBackupIds(
    val polarDeviceId: String? = null,
    val scaleAddress: String? = null,
)

object DeviceBackupParser {

    fun toMarkdown(ids: DeviceBackupIds): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "type" to "device-ids",
            "version" to 1,
        )
        // Absent until set — same "missing key means unset" convention as UserProfileParser.
        ids.polarDeviceId?.takeIf { it.isNotBlank() }?.let { frontmatter["polarDeviceId"] = it }
        ids.scaleAddress?.takeIf { it.isNotBlank() }?.let { frontmatter["scaleAddress"] = it }
        return MarkdownParser.serialize(frontmatter, "")
    }

    /**
     * Returns `null` when [content] has no usable frontmatter (missing/empty/corrupt file)
     * so the caller keeps whatever the `SharedPreferences` already hold. Lenient per-key:
     * a blank value reads as unset rather than discarding the other device.
     */
    fun fromMarkdown(content: String): DeviceBackupIds? {
        val doc = runCatching { MarkdownParser.parse(content) }.getOrNull() ?: return null
        if (doc.frontmatter.isEmpty()) return null
        val fm = doc.frontmatter
        fun deviceKey(key: String): String? =
            fm[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val ids = DeviceBackupIds(
            polarDeviceId = deviceKey("polarDeviceId"),
            scaleAddress = deviceKey("scaleAddress"),
        )
        // A well-formed file that names neither device carries no information — treat it
        // like a missing file so callers don't overwrite prefs with emptiness.
        if (ids.polarDeviceId == null && ids.scaleAddress == null) return null
        return ids
    }
}
