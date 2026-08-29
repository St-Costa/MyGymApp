package com.mygymapp.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [filterBreakingSupersetLinks] drops elements and clears the forward superset link of any
 * survivor whose original successor was removed. Pins the fix for a fabricated superset in the
 * active session when a fixed-daily exercise that is also in the routine gets filtered out of
 * the daily section mid-chain — see
 * [ActiveRoutineViewModel][com.mygymapp.ui.screen.activeroutine.ActiveRoutineViewModel].
 */
class FilterBreakingSupersetLinksTest {

    /** id + "links to next" flag. */
    private data class Item(val id: String, val linked: Boolean)

    private fun run(items: List<Item>, keep: (Item) -> Boolean): List<Item> =
        filterBreakingSupersetLinks(
            items = items,
            linked = { it.linked },
            linkOff = { it.copy(linked = false) },
            keep = keep,
        )

    @Test
    fun `dropping a mid-chain member clears the predecessor link`() {
        // A links->B links->C. B is dropped. A must NOT end up linked to C.
        val out = run(
            listOf(Item("A", true), Item("B", true), Item("C", false)),
        ) { it.id != "B" }
        assertEquals(listOf(Item("A", false), Item("C", false)), out)
    }

    @Test
    fun `dropping the tail of a pair clears the survivor link`() {
        val out = run(listOf(Item("A", true), Item("B", false))) { it.id != "B" }
        assertEquals(listOf(Item("A", false)), out)
    }

    @Test
    fun `an intact chain is left untouched`() {
        val items = listOf(Item("A", true), Item("B", true), Item("C", false))
        assertEquals(items, run(items) { true })
    }

    @Test
    fun `dropping a non-linked element does not disturb a later chain`() {
        // X (single) then A links->B. Drop X. A->B survives intact.
        val out = run(
            listOf(Item("X", false), Item("A", true), Item("B", false)),
        ) { it.id != "X" }
        assertEquals(listOf(Item("A", true), Item("B", false)), out)
    }

    @Test
    fun `dropping the element before a linked survivor keeps that survivor's link`() {
        // A(single), B links->C. Drop A. B->C intact because B's successor C is kept.
        val out = run(
            listOf(Item("A", false), Item("B", true), Item("C", false)),
        ) { it.id != "A" }
        assertEquals(listOf(Item("B", true), Item("C", false)), out)
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<Item>(), run(emptyList()) { true })
    }

    @Test
    fun `dropping every element yields empty`() {
        assertEquals(
            emptyList<Item>(),
            run(listOf(Item("A", true), Item("B", false))) { false },
        )
    }

    @Test
    fun `chain of three with the last dropped clears only the new tail link`() {
        // A links->B links->C, drop C. Result A links->B, B link cleared.
        val out = run(
            listOf(Item("A", true), Item("B", true), Item("C", false)),
        ) { it.id != "C" }
        assertEquals(listOf(Item("A", true), Item("B", false)), out)
    }
}
