package com.mygymapp.data.polar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Year

/**
 * Sprint 5: `UserProfile.hrMax` is the single source of truth for estimated max HR —
 * the same 3-formula mean the live zone classifier and the server use — so TRIMP, the
 * uneven gate, VO2max and the zone boundaries can't diverge by 2–4 BPM anymore.
 */
class UserProfileHrMaxTest {

    @Test
    fun `hrMax delegates to the shared 3-formula mean`() {
        val profile = UserProfile(birthYear = 1990)
        val age = Year.now().value - 1990

        assertEquals(HrZoneCalculator.estimatedMaxHr(age), profile.hrMax)
    }

    @Test
    fun `hrMax uses the default age until birthYear is set`() {
        val profile = UserProfile()

        assertEquals(HrZoneCalculator.estimatedMaxHr(30), profile.hrMax)
    }

    @Test
    fun `hrMax differs from Tanaka-only (the old divergence)`() {
        // Guard against regressing to the single-formula value — pick an age where the
        // mean provably differs from Tanaka alone.
        // Age 40: Fox 180, Tanaka 180, Gulati 170.8 → mean 176.93 → 176, Tanaka 180.
        val profile = UserProfile(birthYear = Year.now().value - 40)

        assertEquals(176, profile.hrMax)
        assertEquals(180, (208 - 0.7 * 40).toInt())
    }
}
