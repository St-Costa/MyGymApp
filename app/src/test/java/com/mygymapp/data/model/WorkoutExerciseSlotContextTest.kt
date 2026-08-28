package com.mygymapp.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Coverage for [WorkoutExercise.slotContext] — the three-way (normal / warmup / daily)
 * classification that StrengthExerciseViewModel and SupersetViewModel use to compare a slot's
 * history like-with-like. Regression here re-opens the bug where a fixed-daily slot's all-time
 * PR was drawn from unrelated routine history (or vice versa), so the daily screen could show a
 * "PR" below its own recent sets. See docs/CHANGELOG.md.
 */
class WorkoutExerciseSlotContextTest {

    private fun slot(daily: Boolean, excludeFromTonnage: Boolean) = WorkoutExercise(
        exerciseId = "ex-cc43b817",
        exerciseName = "Bulgarian glute",
        bodypart = "Glute",
        type = ExerciseType.FORZA,
        isDaily = daily,
        excludeFromTonnage = excludeFromTonnage,
    )

    @Test
    fun `plain routine slot is NORMAL`() {
        assertEquals(SlotContext.NORMAL, slot(daily = false, excludeFromTonnage = false).slotContext)
    }

    @Test
    fun `warmup slot is WARMUP`() {
        // Warmup: excluded from tonnage but not a fixed-daily entry.
        assertEquals(SlotContext.WARMUP, slot(daily = false, excludeFromTonnage = true).slotContext)
    }

    @Test
    fun `fixed-daily slot is DAILY even though it is also excluded from tonnage`() {
        // Daily entries always carry excludeFromTonnage = true, but are run at full intensity —
        // isDaily wins, so their history stays separate from warmup history.
        assertEquals(SlotContext.DAILY, slot(daily = true, excludeFromTonnage = true).slotContext)
    }

    @Test
    fun `daily wins even if excludeFromTonnage was somehow not set`() {
        assertEquals(SlotContext.DAILY, slot(daily = true, excludeFromTonnage = false).slotContext)
    }

    @Test
    fun `the same exercise performed daily vs normal lands in different contexts`() {
        val asDaily = slot(daily = true, excludeFromTonnage = true).slotContext
        val asNormal = slot(daily = false, excludeFromTonnage = false).slotContext
        assertNotEquals(asDaily, asNormal)
    }
}
