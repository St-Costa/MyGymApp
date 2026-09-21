package com.mygymapp.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [filterStitchingSupersetLinks] drops elements and stitches the surviving members of a
 * superset chain back together: a survivor keeps its forward link iff the next survivor
 * belongs to the same original chain. Pins the daily-section behaviour — a fixed-daily
 * exercise that is also in the routine is filtered out of the daily section, and the
 * remaining daily members of its chain stay paired instead of going single — see
 * [ActiveRoutineViewModel][com.mygymapp.ui.screen.activeroutine.ActiveRoutineViewModel].
 */
class FilterStitchingSupersetLinksTest {

    /** id + "links to next" flag. */
    private data class Item(val id: String, val linked: Boolean)

    private fun run(items: List<Item>, keep: (Item) -> Boolean): List<Item> =
        filterStitchingSupersetLinks(
            items = items,
            linked = { it.linked },
            linkOff = { it.copy(linked = false) },
            keep = keep,
        )

    @Test
    fun `dropping a mid-chain member stitches the survivors`() {
        // A links->B links->C. B is dropped. A stays linked to C (2-superset).
        val out = run(
            listOf(Item("A", true), Item("B", true), Item("C", false)),
        ) { it.id != "B" }
        assertEquals(listOf(Item("A", true), Item("C", false)), out)
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

    @Test
    fun `dropping the head of a three chain keeps the remaining pair`() {
        // A links->B links->C, drop A. Result B links->C.
        val out = run(
            listOf(Item("A", true), Item("B", true), Item("C", false)),
        ) { it.id != "A" }
        assertEquals(listOf(Item("B", true), Item("C", false)), out)
    }

    @Test
    fun `dropping does not re-link across an original chain boundary`() {
        // [A links->B] [C links->D]. Drop B: A's chain ends with it, so A goes single
        // and must NOT re-link to C. C->D survives intact.
        val out = run(
            listOf(Item("A", true), Item("B", false), Item("C", true), Item("D", false)),
        ) { it.id != "B" }
        assertEquals(
            listOf(Item("A", false), Item("C", true), Item("D", false)),
            out,
        )
    }

    @Test
    fun `dropping two mid-chain members stitches the outer survivors`() {
        // A links->B links->C links->D, drop B and C. Result A links->D.
        val out = run(
            listOf(Item("A", true), Item("B", true), Item("C", true), Item("D", false)),
        ) { it.id != "B" && it.id != "C" }
        assertEquals(listOf(Item("A", true), Item("D", false)), out)
    }
}
