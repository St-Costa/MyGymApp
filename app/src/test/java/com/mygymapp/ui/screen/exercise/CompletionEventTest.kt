package com.mygymapp.ui.screen.exercise

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 3 regression: navigation must fire off the one-shot [ExerciseSessionViewModel.completionEvent],
 * not a `LaunchedEffect(boolean-flag)` — the flag stays true across recompositions (animated
 * transitions, slow pops) and can re-trigger `onComplete()`, while the replay-less SharedFlow
 * delivers each completion exactly once.
 */
class CompletionEventTest {

    private class FakeVm : ExerciseSessionViewModel() {
        var exitSaved = false
        override fun saveProgressOnExit() {
            exitSaved = true
        }

        fun complete() = markCompletionAndSave { /* disk write */ }
    }

    @Test
    fun `completion emits the event and flips the flag together`() = runBlocking {
        val vm = FakeVm()
        vm.complete()

        val event = withTimeoutOrNull(5_000) { vm.completionEvent.first() }
        assertNotNull("completionEvent delivered", event)
        assertTrue("completionSaved flag still flipped", vm.completionSaved.value)
    }

    @Test
    fun `second tap emits nothing more (exactly-once navigation)`() = runBlocking {
        val vm = FakeVm()
        vm.complete()
        withTimeoutOrNull(5_000) { vm.completionEvent.first() }

        vm.complete() // idempotent via `finished` — must not emit again

        val second = withTimeoutOrNull(300) { vm.completionEvent.first() }
        assertNull("no second event", second)
    }

    @Test
    fun `no event before completion`() = runBlocking {
        val vm = FakeVm()

        val event = withTimeoutOrNull(300) { vm.completionEvent.first() }
        assertNull("nothing emitted before markCompletionAndSave", event)
    }
}
