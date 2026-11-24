package com.mygymapp.data.polar

import android.content.Context
import com.mygymapp.data.device.KnownDeviceBackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the Polar device ID the user paired with, so future scans auto-connect to it.
 *
 * The ID is mirrored to `exercises/_devices.md` via [KnownDeviceBackupRepository] on every
 * set/forget, and an empty prefs entry self-heals from that file on read — prefs alone are
 * wiped by every reinstall, the file rides the tar + server backups (see docs/BACKUP.md §6).
 */
@Singleton
class KnownPolarDeviceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val deviceBackup: KnownDeviceBackupRepository,
) {
    private val prefs = context.getSharedPreferences("known_polar_device", Context.MODE_PRIVATE)

    fun getKnownDeviceId(): String? {
        prefs.getString("deviceId", null)?.let { return it }
        // Post-reinstall: prefs are empty but the mirror file may have come back via the
        // tar backup or a server "Ripristina" — heal prefs so scanning auto-connects.
        return deviceBackup.snapshot()?.polarDeviceId?.also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    fun setKnownDeviceId(deviceId: String) {
        prefs.edit().putString("deviceId", deviceId).apply()
        deviceBackup.savePolarSoon(deviceId)
    }

    fun forget() {
        prefs.edit().remove("deviceId").apply()
        deviceBackup.savePolarSoon(null)
    }

    /**
     * Best-effort heal after a server "Ripristina" rewrote the mirror file bypassing this
     * repo (mirrors the `profileRepo.refresh()` pattern in OptionsViewModel).
     */
    fun healFromBackup() {
        if (prefs.contains("deviceId")) return
        deviceBackup.snapshot()?.polarDeviceId?.let {
            prefs.edit().putString("deviceId", it).apply()
        }
    }
}
