package com.mygymapp.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the "powerlifting week" schedule: an anchor week (Monday) and a recurrence
 * interval in weeks. The anchor week itself is a powerlifting week, and so is every
 * Nth week after it (interval = 4 means 3 normal weeks + 1 powerlifting week).
 *
 * When [isPowerliftingWeek] is true for the current week, opening a session shows the
 * "SETTIMANA POWERLIFTING" overlay (see ActiveRoutineScreen).
 */
@Singleton
class PowerliftingScheduleRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("powerlifting_schedule", Context.MODE_PRIVATE)

    /** Anchor week as the ISO date of its Monday, or null if the schedule is disabled. */
    fun anchorMonday(): LocalDate? =
        prefs.getString("anchorMonday", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Recurrence interval in weeks. Default 4 = one powerlifting week every 4 weeks. */
    fun intervalWeeks(): Int = prefs.getInt("intervalWeeks", 4)

    fun save(anchorMonday: LocalDate?, intervalWeeks: Int) {
        prefs.edit()
            .putString("anchorMonday", anchorMonday?.toString())
            .putInt("intervalWeeks", intervalWeeks.coerceAtLeast(1))
            .apply()
    }

    /** True if [date]'s week is a powerlifting week given the configured anchor + interval. */
    fun isPowerliftingWeek(date: LocalDate = LocalDate.now()): Boolean {
        val anchor = anchorMonday() ?: return false
        val interval = intervalWeeks().coerceAtLeast(1)
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val weeksBetween = ChronoUnit.WEEKS.between(anchor, monday)
        return Math.floorMod(weeksBetween, interval.toLong()) == 0L
    }
}
