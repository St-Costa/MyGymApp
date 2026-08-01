package com.mygymapp.data.polar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers the Polar device ID the user paired with, so future scans auto-connect to it. */
@Singleton
class KnownPolarDeviceRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("known_polar_device", Context.MODE_PRIVATE)

    fun getKnownDeviceId(): String? = prefs.getString("deviceId", null)

    fun setKnownDeviceId(deviceId: String) {
        prefs.edit().putString("deviceId", deviceId).apply()
    }

    fun forget() {
        prefs.edit().remove("deviceId").apply()
    }
}
