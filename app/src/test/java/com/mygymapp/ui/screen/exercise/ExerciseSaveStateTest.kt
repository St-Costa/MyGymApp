package com.mygymapp.ui.screen.exercise

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the explicit save-state teardown (ISM-03): [ExerciseSaveState] drives
 * `onCleared` exhaustively — Completing/Switched join, Idle exit-saves, and a
 * switch never drops an in-flight completion write.
 */
class ExerciseSaveStateTest {

    private class FakeVm : ExerciseSessionViewModel() {
        var exitSaved = false
        var writeRan = false

        override fun saveProgressOnExit() {
            exitSaved = true
        }

        fun completeSlow() = markCompletionAndSave {
            delay(300)
            writeRan = true
        }

        fun switched() = markSwitched()

        // ViewModel.onCleared is protected — expose it like the framework calls it.
        fun cleared() = onCleared()
    }

    @Test
    fun `idle teardown takes the exit-save path`() {
        val vm = FakeVm()
        vm.cleared()
        assertTrue("Idle onCleared exit-saves", vm.exitSaved)
        assertFalse(vm.writeRan)
    }

    @Test
    fun `switched teardown never exit-saves`() {
        val vm = FakeVm()
        vm.switched()
        vm.cleared()
        assertFalse("Switched onCleared must not re-save", vm.exitSaved)
    }

    @Test
    fun `completing teardown never exit-saves and the write still lands`() = runBlocking {
        val vm = FakeVm()
        vm.completeSlow()
        vm.cleared()
        assertFalse(vm.exitSaved)
        val landed = withTimeoutOrNull(5_000) { vm.completionEvent.first() }
        assertNotNull("completion still delivered after teardown", landed)
        assertTrue(vm.writeRan)
    }

    @Test
    fun `switch during a running completion still lands the write`() = runBlocking {
        val vm = FakeVm()
        vm.completeSlow()
        vm.switched() // complete-then-switch in one navigation: job carried along
        vm.cleared()
        assertFalse(vm.exitSaved)
        val landed = withTimeoutOrNull(5_000) { vm.completionEvent.first() }
        assertNotNull("in-flight completion joined, not dropped", landed)
        assertTrue(vm.writeRan)
    }

    @Test
    fun `complete after switch stays silent (first request wins)`() = runBlocking {
        val vm = FakeVm()
        vm.switched()
        vm.completeSlow()
        val second = withTimeoutOrNull(600) { vm.completionEvent.first() }
        assertTrue("no completion from a switched instance", second == null)
        // Give the slow block a chance to prove it never ran.
        withTimeoutOrNull(600) { while (vm.writeRan) delay(50) }
        assertFalse(vm.writeRan)
    }
}
