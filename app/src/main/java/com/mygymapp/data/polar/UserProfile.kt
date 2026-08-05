package com.mygymapp.data.polar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class UserProfile(
    val age: Int = 30,
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
    val heightCm: Int = 175,
) {
    /** Tanaka formula: more accurate than 220-age */
    val hrMax: Int get() = (208 - (0.7 * age)).toInt()
}

@Singleton
class UserProfileRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    fun get(): UserProfile = UserProfile(
        age = prefs.getInt("age", 30),
        weightKg = prefs.getFloat("weightKg", 75f).toDouble(),
        isMale = prefs.getBoolean("isMale", true),
        heightCm = prefs.getInt("heightCm", 175),
    )

    fun save(profile: UserProfile) {
        prefs.edit()
            .putInt("age", profile.age)
            .putFloat("weightKg", profile.weightKg.toFloat())
            .putBoolean("isMale", profile.isMale)
            .putInt("heightCm", profile.heightCm)
            .apply()
    }
}
