package com.mygymapp.data.scale

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers the MAC address of the scale the user paired with, so future scans auto-connect to it. */
@Singleton
class KnownScaleRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("known_scale", Context.MODE_PRIVATE)

    fun getKnownAddress(): String? = prefs.getString("address", null)

    fun setKnownAddress(address: String) {
        prefs.edit().putString("address", address).apply()
    }

    fun forget() {
        prefs.edit().remove("address").apply()
    }
}
