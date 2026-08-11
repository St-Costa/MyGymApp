package com.mygymapp.data.polar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Year
import javax.inject.Inject
import javax.inject.Singleton

data class UserProfile(
    val age: Int = 30,
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
    val heightCm: Int = 175,
    // Real birth year, if the user has entered one. Preferred over [age] wherever a
    // computation wants "current age" — it stays correct as the calendar year rolls over,
    // instead of needing a manual yearly bump like the plain [age] field does. Null until
    // the user fills it in (see ProfileSection); [age] remains the fallback everywhere.
    val birthYear: Int? = null,
) {
    /** Tanaka formula: more accurate than 220-age */
    val hrMax: Int get() = (208 - (0.7 * age)).toInt()

    /** [birthYear]-derived age when available, else the manually-set [age]. */
    val effectiveAge: Int get() = birthYear?.let { Year.now().value - it } ?: age
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
        birthYear = prefs.getInt("birthYear", -1).takeIf { it > 0 },
    )

    fun save(profile: UserProfile) {
        prefs.edit()
            .putInt("age", profile.age)
            .putFloat("weightKg", profile.weightKg.toFloat())
            .putBoolean("isMale", profile.isMale)
            .putInt("heightCm", profile.heightCm)
            .apply {
                if (profile.birthYear != null) putInt("birthYear", profile.birthYear) else remove("birthYear")
            }
            .apply()
    }
}
