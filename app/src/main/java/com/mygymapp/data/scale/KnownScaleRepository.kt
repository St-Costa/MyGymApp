package com.mygymapp.data.scale

import android.content.Context
import com.mygymapp.data.device.KnownDeviceBackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the MAC address of the scale the user paired with, so future scans auto-connect to it.
 *
 * The address is mirrored to `exercises/_devices.md` via [KnownDeviceBackupRepository] on
 * every set/forget, and an empty prefs entry self-heals from that file on read — prefs alone
 * are wiped by every reinstall, the file rides the tar + server backups (see docs/BACKUP.md §6).
 */
@Singleton
class KnownScaleRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val deviceBackup: KnownDeviceBackupRepository,
) {
    private val prefs = context.getSharedPreferences("known_scale", Context.MODE_PRIVATE)

    fun getKnownAddress(): String? {
        prefs.getString("address", null)?.let { return it }
        // Post-reinstall: prefs are empty but the mirror file may have come back via the
        // tar backup or a server "Ripristina" — heal prefs so scanning auto-connects.
        return deviceBackup.snapshot()?.scaleAddress?.also {
            prefs.edit().putString("address", it).apply()
        }
    }

    fun setKnownAddress(address: String) {
        prefs.edit().putString("address", address).apply()
        deviceBackup.saveScaleSoon(address)
    }

    fun forget() {
        prefs.edit().remove("address").apply()
        deviceBackup.saveScaleSoon(null)
    }

    /**
     * Best-effort heal after a server "Ripristina" rewrote the mirror file bypassing this
     * repo (mirrors the `profileRepo.refresh()` pattern in OptionsViewModel).
     */
    fun healFromBackup() {
        if (prefs.contains("address")) return
        deviceBackup.snapshot()?.scaleAddress?.let {
            prefs.edit().putString("address", it).apply()
        }
    }
}
