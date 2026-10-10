package com.mygymapp.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Readiness-beep volume (0–100, default 100), Sprint 7. Plain local config in
 * `SharedPreferences("sound")` — same tier as the powerlifting schedule, not user
 * data: never synced, never in `gymdata/`, covered by the `shared_prefs/` tar.
 * Previewed from the Options slider (release plays one beep at that volume).
 */
@Singleton
class BeepVolumeRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun volume(): Int = coerceBeepVolume(prefs.getInt(KEY_VOLUME, DEFAULT_BEEP_VOLUME))

    fun setVolume(volume: Int) {
        prefs.edit().putInt(KEY_VOLUME, coerceBeepVolume(volume)).apply()
    }

    companion object {
        const val DEFAULT_BEEP_VOLUME = 100

        /** Pure — unit-tested. */
        fun coerceBeepVolume(volume: Int): Int = volume.coerceIn(0, 100)

        private const val STORE = "sound"
        private const val KEY_VOLUME = "beep_volume"
    }
}
