package com.mygymapp.data.parser

import com.mygymapp.data.model.DayCellStatus
import com.mygymapp.data.model.GitgraphDay
import com.mygymapp.data.model.GitgraphHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GitgraphHistoryParserTest {

    private fun roundTrip(h: GitgraphHistory): GitgraphHistory {
        val yaml = GitgraphHistoryParser.toYaml(h)
        return GitgraphHistoryParser.fromYaml(yaml) ?: error("null round-trip for:\n$yaml")
    }

    private fun fullWindow(start: LocalDate, mutate: (Int) -> GitgraphDay): GitgraphHistory =
        GitgraphHistory(
            windowStartMonday = start.toString(),
            days = (0 until 28).map { mutate(it) },
        )

    @Test
    fun `all-NONE window round-trips`() {
        val start = LocalDate.of(2026, 7, 27)
        val h = fullWindow(start) { i -> GitgraphDay(date = start.plusDays(i.toLong()).toString()) }

        val r = roundTrip(h)

        assertEquals(GitgraphHistory.SCHEMA_VERSION, r.schemaVersion)
        assertEquals("2026-07-27", r.windowStartMonday)
        assertEquals(28, r.days.size)
        assertEquals("2026-08-23", r.days.last().date)
        assertEquals(DayCellStatus.NONE, r.days[5].status)
        assertNull(r.days[5].tonnageChangePct)
        assertNull(r.days[5].sessionId)
    }

    @Test
    fun `mixed window with all field kinds round-trips`() {
        val start = LocalDate.of(2026, 7, 27)
        val h = fullWindow(start) { i ->
            val d = start.plusDays(i.toLong()).toString()
            when (i) {
                3 -> GitgraphDay(d, DayCellStatus.IMPROVED, tonnageChangePct = 3.4,
                    routineName = "PUSH", sessionId = "abc12345")
                10 -> GitgraphDay(d, DayCellStatus.REGRESSED, tonnageChangePct = -12.7,
                    routineName = "PULL", sessionId = "def67890")
                17 -> GitgraphDay(d, DayCellStatus.IMPROVED, cardioMinutes = 42,
                    routineName = "CARDIO", sessionId = "caf3f00d")
                20 -> GitgraphDay(d, DayCellStatus.IMPROVED, stretchMinutes = 7,
                    routineName = "MOBILITY", sessionId = "stretch01")
                24 -> GitgraphDay(d, DayCellStatus.IMPROVED, // first session, no %
                    routineName = "LEGS", sessionId = "1a2b3c4d")
                else -> GitgraphDay(d)
            }
        }

        val r = roundTrip(h)

        assertEquals(DayCellStatus.IMPROVED, r.days[3].status)
        assertEquals(3.4, r.days[3].tonnageChangePct!!, 1e-9)
        assertEquals("PUSH", r.days[3].routineName)
        assertEquals("abc12345", r.days[3].sessionId)

        assertEquals(-12.7, r.days[10].tonnageChangePct!!, 1e-9)

        assertEquals(42, r.days[17].cardioMinutes)
        assertNull(r.days[17].tonnageChangePct)

        assertEquals(7, r.days[20].stretchMinutes)
        assertNull(r.days[20].cardioMinutes)

        assertEquals(DayCellStatus.IMPROVED, r.days[24].status)
        assertNull(r.days[24].tonnageChangePct)
        assertEquals("LEGS", r.days[24].routineName)
    }

    @Test
    fun `routine name with a quote round-trips`() {
        val start = LocalDate.of(2026, 7, 27)
        val h = fullWindow(start) { i ->
            val d = start.plusDays(i.toLong()).toString()
            if (i == 0) GitgraphDay(d, DayCellStatus.IMPROVED, routineName = "\"Leg\" day",
                sessionId = "q1w2e3r4")
            else GitgraphDay(d)
        }
        val r = roundTrip(h)
        assertEquals("\"Leg\" day", r.days[0].routineName)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(GitgraphHistoryParser.fromYaml(""))
        assertNull(GitgraphHistoryParser.fromYaml("nonsense"))
        assertNull(GitgraphHistoryParser.fromYaml("---\nschemaVersion: 1\n---"))
    }

    @Test
    fun `old schema still parses, version preserved`() {
        val yaml = """
            ---
            schemaVersion: 0
            windowStartMonday: "2026-07-27"
            days:
              - date: "2026-07-27"
                status: "NONE"
            ---
        """.trimIndent()
        val r = GitgraphHistoryParser.fromYaml(yaml)
        assertNotNull(r)
        assertEquals(0, r!!.schemaVersion)
        assertEquals(1, r.days.size)
    }
}
