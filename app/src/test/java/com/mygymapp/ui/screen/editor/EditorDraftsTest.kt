package com.mygymapp.ui.screen.editor

import androidx.lifecycle.SavedStateHandle
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.ui.screen.exerciseedit.DRAFT_BODYPART
import com.mygymapp.ui.screen.exerciseedit.DRAFT_LINK
import com.mygymapp.ui.screen.exerciseedit.DRAFT_NAME
import com.mygymapp.ui.screen.exerciseedit.DRAFT_NOTES
import com.mygymapp.ui.screen.exerciseedit.ExerciseEditUiState
import com.mygymapp.ui.screen.exerciseedit.ExerciseTextDraft
import com.mygymapp.ui.screen.exerciseedit.readExerciseTextDraft
import com.mygymapp.ui.screen.exerciseedit.withTextDraft as withExerciseTextDraft
import com.mygymapp.ui.screen.routineedit.DRAFT_ROUTINE_DAY
import com.mygymapp.ui.screen.routineedit.DRAFT_ROUTINE_NAME
import com.mygymapp.ui.screen.routineedit.RoutineEditUiState
import com.mygymapp.ui.screen.routineedit.RoutineTextDraft
import com.mygymapp.ui.screen.routineedit.readRoutineTextDraft
import com.mygymapp.ui.screen.routineedit.withTextDraft as withRoutineTextDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 3: editor text drafts survive process death via SavedStateHandle.
 * (The ViewModel wiring itself needs Android — these pin the pure read/overlay half.)
 */
class EditorDraftsTest {

    @Test
    fun `empty handle reads all-null drafts`() {
        val handle = SavedStateHandle()

        assertEquals(
            ExerciseTextDraft(null, null, null, null),
            readExerciseTextDraft(handle),
        )
        assertEquals(RoutineTextDraft(null, null), readRoutineTextDraft(handle))
    }

    @Test
    fun `handle round-trips keystrokes`() {
        val handle = SavedStateHandle()
        handle[DRAFT_NAME] = "Panca"
        handle[DRAFT_BODYPART] = "petto"
        handle[DRAFT_LINK] = "https://x"
        handle[DRAFT_NOTES] = "nota"
        handle[DRAFT_ROUTINE_NAME] = "Lunedì"
        handle[DRAFT_ROUTINE_DAY] = "monday"

        assertEquals(
            ExerciseTextDraft("Panca", "petto", "https://x", "nota"),
            readExerciseTextDraft(handle),
        )
        assertEquals(RoutineTextDraft("Lunedì", "monday"), readRoutineTextDraft(handle))
    }

    @Test
    fun `overlay prefers draft, keeps state for missing keys`() {
        val state = ExerciseEditUiState(
            name = "Old",
            type = ExerciseType.FORZA,
            bodypart = "schiena",
            link = "",
            notes = "keep",
        )

        val overlaid = state.withExerciseTextDraft(
            ExerciseTextDraft(name = "New", bodypart = null, link = "https://y", notes = null),
        )

        assertEquals("New", overlaid.name)
        assertEquals("schiena", overlaid.bodypart)
        assertEquals("https://y", overlaid.link)
        assertEquals("keep", overlaid.notes)
    }

    @Test
    fun `routine overlay prefers draft`() {
        val state = RoutineEditUiState(name = "Old", day = "tuesday")

        val overlaid = state.withRoutineTextDraft(RoutineTextDraft(name = null, day = "monday"))

        assertEquals("Old", overlaid.name)
        assertEquals("monday", overlaid.day)
    }

    @Test
    fun `all-null draft leaves state untouched`() {
        val state = ExerciseEditUiState(name = "N", bodypart = "B", link = "L", notes = "T")

        assertEquals(state, state.withExerciseTextDraft(ExerciseTextDraft(null, null, null, null)))
        assertNull(readExerciseTextDraft(SavedStateHandle()).name)
    }
}
