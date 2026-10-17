package com.mygymapp.ui.screen.activeroutine

import com.mygymapp.data.model.ExerciseType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [ActiveExerciseUi.isReopenable], the active-routine row gate that lets a
 * skipped exercise be re-entered (see docs/CONVENTIONS.md "Re-entering a skipped exercise").
 */
class ActiveExerciseReopenableTest {

    private fun row(completed: Boolean, completedEmpty: Boolean) = ActiveExerciseUi(
        exerciseId = "ex-aaaabbbb",
        exerciseName = "Bench press",
        type = ExerciseType.FORZA,
        bodypart = "chest",
        completed = completed,
        completedEmpty = completedEmpty,
    )

    @Test
    fun `an open slot is reopenable`() {
        assertTrue(row(completed = false, completedEmpty = false).isReopenable())
    }

    @Test
    fun `a skipped slot is reopenable`() {
        assertTrue(row(completed = true, completedEmpty = true).isReopenable())
    }

    @Test
    fun `a completed-and-performed slot stays locked`() {
        assertFalse(row(completed = true, completedEmpty = false).isReopenable())
    }
}
