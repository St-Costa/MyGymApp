package com.mygymapp.data.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class BreadcrumbsTest {

    private var now = 0L
    private fun buffer(capacity: Int = 5) = BreadcrumbBuffer(capacity) { now++ }

    @Test
    fun `keeps only the newest crumbs, oldest first`() {
        val b = buffer(capacity = 3)
        (1..5).forEach { b.record("t", "m$it") }
        assertEquals(listOf("m3", "m4", "m5"), b.snapshot().map { it.message })
    }

    @Test
    fun `same key back to back coalesces into the latest value`() {
        val b = buffer()
        b.record("strength", "set 1 weight → 60.0", key = "strength.weight.0")
        b.record("strength", "set 1 weight → 62.5", key = "strength.weight.0")
        b.record("strength", "set 1 weight → 65.0", key = "strength.weight.0")
        val snap = b.snapshot()
        assertEquals(1, snap.size)
        assertEquals("set 1 weight → 65.0", snap.single().message)
    }

    @Test
    fun `a different key in between breaks the run`() {
        val b = buffer()
        b.record("s", "w1", key = "w")
        b.record("s", "r1", key = "r")
        b.record("s", "w2", key = "w")
        assertEquals(listOf("w1", "r1", "w2"), b.snapshot().map { it.message })
    }

    @Test
    fun `keyless crumbs never coalesce`() {
        val b = buffer()
        b.record("nav", "main")
        b.record("nav", "main")
        assertEquals(2, b.snapshot().size)
    }

    @Test
    fun `coalescing at capacity does not evict an older crumb`() {
        val b = buffer(capacity = 2)
        b.record("t", "a")
        b.record("t", "w1", key = "w")
        b.record("t", "w2", key = "w")
        assertEquals(listOf("a", "w2"), b.snapshot().map { it.message })
    }

    @Test
    fun `trail format is time, category, message per line`() {
        val crumbs = listOf(
            Breadcrumb(atMillis = 3_723_004L, category = "nav", message = "main"),
            Breadcrumb(atMillis = 3_724_000L, category = "strength", message = "set 1 weight → 60.0"),
        )
        assertEquals(
            "01:02:03.004 [nav] main\n01:02:04.000 [strength] set 1 weight → 60.0",
            crumbs.formatTrail(ZoneOffset.UTC),
        )
    }

    @Test
    fun `describeRoute substitutes known args and leaves the rest`() {
        assertEquals(
            "workout/ab12cd34/strength/ex-0011aabb",
            describeRoute(
                "workout/{sessionId}/strength/{exerciseId}",
                mapOf("sessionId" to "ab12cd34", "exerciseId" to "ex-0011aabb"),
            ),
        )
        assertEquals("routines/edit?id={id}", describeRoute("routines/edit?id={id}", emptyMap()))
        assertEquals("routines/edit?id=", describeRoute("routines/edit?id={id}", mapOf("id" to null)))
        assertEquals("?", describeRoute(null, emptyMap()))
    }
}
