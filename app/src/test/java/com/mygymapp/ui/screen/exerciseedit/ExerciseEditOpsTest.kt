package com.mygymapp.ui.screen.exerciseedit

import com.mygymapp.data.model.DEFAULT_BW_LOAD_PERCENT
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.LoadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit exercise-editor rules (ISM-28): [applyBodyweightChange],
 * [applyAssistedChange], [applyRepMinChange]/[applyRepMaxChange],
 * [ExerciseEditUiState.isSavable] and [ExerciseEditUiState.toExercise].
 */
class ExerciseEditOpsTest {

    private fun state() = ExerciseEditUiState(
        id = "ex-1",
        name = "Bench press",
        type = ExerciseType.FORZA,
        bodypart = "chest",
        defaultRepRangeMin = 8,
        defaultRepRangeMax = 12,
    )

    // ─── Load-mode exclusion ───

    @Test
    fun `bodyweight on clears assisted and reseeds out-of-range percent`() {
        val result = applyBodyweightChange(
            state().copy(isAssisted = true, bwLoadPercent = 999),
            true,
        )
        assertTrue(result.isBodyweight)
        assertFalse(result.isAssisted)
        assertEquals(DEFAULT_BW_LOAD_PERCENT, result.bwLoadPercent)
    }

    @Test
    fun `bodyweight on keeps an in-range percent`() {
        val result = applyBodyweightChange(state().copy(bwLoadPercent = 75), true)
        assertEquals(75, result.bwLoadPercent)
    }

    @Test
    fun `bodyweight off leaves assisted alone`() {
        val result = applyBodyweightChange(state().copy(isAssisted = true), false)
        assertFalse(result.isBodyweight)
        assertTrue(result.isAssisted)
    }

    @Test
    fun `assisted on clears bodyweight, off leaves it alone`() {
        val on = applyAssistedChange(state().copy(isBodyweight = true), true)
        assertTrue(on.isAssisted)
        assertFalse(on.isBodyweight)

        val off = applyAssistedChange(state().copy(isBodyweight = true), false)
        assertFalse(off.isAssisted)
        assertTrue(off.isBodyweight)
    }

    // ─── Rep-range coupling ───

    @Test
    fun `min above max pulls max up, max below min pulls min down`() {
        assertEquals(15, applyRepMinChange(state(), 15).defaultRepRangeMax)
        assertEquals(15, applyRepMinChange(state(), 15).defaultRepRangeMin)
        assertEquals(5, applyRepMaxChange(state(), 5).defaultRepRangeMin)
        assertEquals(5, applyRepMaxChange(state(), 5).defaultRepRangeMax)
    }

    @Test
    fun `rep bounds clamp at one`() {
        assertEquals(1, applyRepMinChange(state(), 0).defaultRepRangeMin)
        assertEquals(1, applyRepMaxChange(state(), -3).defaultRepRangeMax)
    }

    // ─── Save guard ───

    @Test
    fun `blank name or deleted never saves`() {
        assertFalse(state().copy(name = "  ").isSavable())
        assertFalse(state().copy(deleted = true).isSavable())
        assertTrue(state().isSavable())
    }

    // ─── Mapping ───

    @Test
    fun `cardio blanks a stale bodypart and trims text`() {
        val exercise = state().copy(
            type = ExerciseType.CARDIO,
            bodypart = "chest",
            name = " Ride ",
        ).toExercise()
        assertEquals("", exercise.bodypart)
        assertEquals("Ride", exercise.name)
    }

    @Test
    fun `load percent persists only for bodyweight, mode priority is bodyweight first`() {
        assertEquals(75, state().copy(isBodyweight = true, bwLoadPercent = 75).toExercise().bwLoadPercent)
        assertEquals(0, state().copy(isBodyweight = false, bwLoadPercent = 75).toExercise().bwLoadPercent)
        assertEquals(
            LoadMode.BODYWEIGHT,
            state().copy(isBodyweight = true, isAssisted = true).toExercise().loadMode,
        )
        assertEquals(LoadMode.ASSISTED, state().copy(isAssisted = true).toExercise().loadMode)
        assertEquals(LoadMode.MANUAL, state().toExercise().loadMode)
    }
}
