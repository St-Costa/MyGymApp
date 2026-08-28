package com.mygymapp.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [groupSupersets] walks a list, folding maximal runs of consecutive `pairedWithNext` items
 * into one group, capped at [MAX_SUPERSET_SIZE]. These cases pin the pairing/capping logic
 * that both the routine editor and the active-routine screen rely on.
 */
class SupersetGroupingTest {

    /** Model each item as its "paired with next" flag; group results as index lists. */
    private fun group(flags: List<Boolean>): List<List<Int>> =
        groupSupersets(
            items = flags,
            isPairedWithNext = { it },
            single = { i -> listOf(i) },
            group = { idxs -> idxs },
        )

    @Test
    fun `no flags means all singles`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), group(listOf(false, false, false)))
    }

    @Test
    fun `empty list yields nothing`() {
        assertEquals(emptyList<List<Int>>(), group(emptyList()))
    }

    @Test
    fun `two consecutive flags form a pair`() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), group(listOf(true, false, false)))
    }

    @Test
    fun `three consecutive flags form one chain of three`() {
        assertEquals(listOf(listOf(0, 1, 2)), group(listOf(true, true, false)))
    }

    @Test
    fun `four consecutive flags are capped - chain of three then a single`() {
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(3)),
            group(listOf(true, true, true, false)),
        )
    }

    @Test
    fun `exactly MAX_SUPERSET_SIZE-1 leading flags form one full-size chain`() {
        // 2 flags on a 3-element list -> [0,1,2] is one chain of exactly MAX_SUPERSET_SIZE.
        assertEquals(listOf(listOf(0, 1, 2)), group(listOf(true, true, false)))
    }

    @Test
    fun `five consecutive flags split into two capped chains`() {
        assertEquals(
            listOf(listOf(0, 1, 2), listOf(3, 4, 5)),
            group(listOf(true, true, true, true, true, false)),
        )
    }

    @Test
    fun `run stops at the end even with a trailing flag inside a full chain`() {
        // [true,true] on a 2-element list: boundary flag on index 1 has nothing after it.
        assertEquals(listOf(listOf(0, 1)), group(listOf(true, true)))
    }

    @Test
    fun `two separate pairs`() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3)),
            group(listOf(true, false, true, false)),
        )
    }

    @Test
    fun `trailing flag on the last item cannot form a chain past the end`() {
        assertEquals(listOf(listOf(0), listOf(1)), group(listOf(false, true)))
    }
}
