package com.mygymapp.data.polar

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Year
import javax.inject.Inject
import javax.inject.Singleton

/** Fallback age (years) used by every age-dependent formula when [UserProfile.birthYear]
 *  hasn't been set yet — matches the old plain-[age]-field default, so a fresh profile keeps
 *  producing the same numbers it always did until the user fills in a real birth year. */
private const val DEFAULT_AGE = 30

data class UserProfile(
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
    val heightCm: Int = 175,
    // Real birth year — the sole source of age for every formula (Keytel calories, Tanaka
    // HRmax, TRIMP, VO2max, BIA body-fat %). Null until the user fills it in (ProfileSection);
    // effectiveAge falls back to DEFAULT_AGE until then.
    val birthYear: Int? = null,
) {
    /** [birthYear]-derived age, recomputed from the current calendar year (never stale like a
     *  manually-entered plain age field would be) — [DEFAULT_AGE] until birthYear is set. */
    val effectiveAge: Int get() = birthYear?.let { Year.now().value - it } ?: DEFAULT_AGE

    /** Tanaka formula: more accurate than 220-age. */
    val hrMax: Int get() = (208 - (0.7 * effectiveAge)).toInt()
}

@Singleton
class UserProfileRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    fun get(): UserProfile = UserProfile(
        weightKg = prefs.getFloat("weightKg", 75f).toDouble(),
        isMale = prefs.getBoolean("isMale", true),
        heightCm = prefs.getInt("heightCm", 175),
        birthYear = prefs.getInt("birthYear", -1).takeIf { it > 0 },
    )

    fun save(profile: UserProfile) {
        prefs.edit()
            .putFloat("weightKg", profile.weightKg.toFloat())
            .putBoolean("isMale", profile.isMale)
            .putInt("heightCm", profile.heightCm)
            .apply {
                if (profile.birthYear != null) putInt("birthYear", profile.birthYear) else remove("birthYear")
            }
            .apply()
    }
}
