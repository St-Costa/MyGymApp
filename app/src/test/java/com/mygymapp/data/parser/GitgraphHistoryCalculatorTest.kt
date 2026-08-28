package com.mygymapp.data.parser

import com.mygymapp.data.model.DayCellStatus
import com.mygymapp.data.model.ExerciseSet
import com.mygymapp.data.model.ExerciseType
import com.mygymapp.data.model.WorkoutExercise
import com.mygymapp.data.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GitgraphHistoryCalculatorTest {

    private fun session(
        id: String,
        routineId: String,
        date: String,
        completedAt: String = "${date}T10:00:00",
        routineName: String = "R",
        strength: List<Triple<String, Int, Double>> = emptyList(), // exId, reps, weight (one set each)
        totalTonnage: Double = strength.sumOf { it.second * it.third },
        cardioBlocks: List<Pair<String, String>> = emptyList(),    // startedAt, endedAt
    ) = WorkoutSession(
        id = id, routineId = routineId, routineName = routineName,
        date = date, completedAt = completedAt, totalTonnage = totalTonnage,
        exercises = buildList {
            strength.groupBy { it.first }.forEach { (exId, rows) ->
                add(
                    WorkoutExercise(
                        exerciseId = exId, exerciseName = exId, bodypart = "b",
                        type = ExerciseType.FORZA, completed = true,
                        sets = rows.map { ExerciseSet.Strength(reps = it.second, weight = it.third) },
                    )
                )
            }
            if (cardioBlocks.isNotEmpty()) {
                add(
                    WorkoutExercise(
                        exerciseId = "cardio", exerciseName = "cardio", bodypart = "b",
                        type = ExerciseType.CARDIO, completed = true, excludeFromTonnage = true,
                        sets = cardioBlocks.map { ExerciseSet.Cardio(startedAt = it.first, endedAt = it.second) },
                    )
                )
            }
        },
    )

    private val MON = LocalDate.of(2026, 7, 27) // a Monday

    @Test
    fun `empty history is 28 NONE days`() {
        val h = GitgraphHistoryCalculator.buildWindow(MON, emptyList(), emptyMap())
        assertEquals(28, h.days.size)
        assertEquals(MON.toString(), h.days.first().date)
        assertEquals(MON.plusDays(27).toString(), h.days.last().date)
        assertEquals(List(28) { DayCellStatus.NONE }, h.days.map { it.status })
    }

    @Test
    fun `first session of a routine is IMPROVED with no percent`() {
        val s = session("s1", "rt-a", MON.plusDays(2).toString(), strength = listOf(Triple("ex1", 10, 50.0)))
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(s), emptyMap())
        val d = h.days[2]
        assertEquals(DayCellStatus.IMPROVED, d.status)
        assertNull(d.tonnageChangePct)
        assertEquals("s1", d.sessionId)
        assertEquals("R", d.routineName)
    }

    @Test
    fun `improvement vs previous same-routine session inside window`() {
        val prev = session("p", "rt-a", MON.plusDays(1).toString(), strength = listOf(Triple("ex1", 10, 50.0))) // 500
        val curr = session("c", "rt-a", MON.plusDays(8).toString(), strength = listOf(Triple("ex1", 10, 55.0))) // 550
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(prev, curr), emptyMap())
        val d = h.days[8]
        assertEquals(DayCellStatus.IMPROVED, d.status)
        assertEquals(10.0, d.tonnageChangePct!!, 1e-9) // +10%
    }

    @Test
    fun `regression vs previous session`() {
        val prev = session("p", "rt-a", MON.plusDays(1).toString(), strength = listOf(Triple("ex1", 10, 50.0)))
        val curr = session("c", "rt-a", MON.plusDays(8).toString(), strength = listOf(Triple("ex1", 8, 50.0))) // 400
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(prev, curr), emptyMap())
        val d = h.days[8]
        assertEquals(DayCellStatus.REGRESSED, d.status)
        assertEquals(-20.0, d.tonnageChangePct!!, 1e-9)
    }

    @Test
    fun `previous session outside the window is honoured via priorSessionsByRoutine`() {
        val prior = session("old", "rt-a", MON.minusWeeks(3).toString(), strength = listOf(Triple("ex1", 10, 40.0))) // 400
        val curr = session("c", "rt-a", MON.plusDays(0).toString(), strength = listOf(Triple("ex1", 10, 50.0)))       // 500
        val h = GitgraphHistoryCalculator.buildWindow(
            MON, listOf(curr), mapOf("rt-a" to listOf(prior)),
        )
        val d = h.days[0]
        assertEquals(DayCellStatus.IMPROVED, d.status)
        assertEquals(25.0, d.tonnageChangePct!!, 1e-9)
    }

    @Test
    fun `common-tonnage compares only shared exercises`() {
        // prev has ex1+ex2, curr has ex1+ex3. Only ex1 is comparable.
        val prev = session("p", "rt-a", MON.plusDays(1).toString(),
            strength = listOf(Triple("ex1", 10, 50.0), Triple("ex2", 10, 100.0)))
        val curr = session("c", "rt-a", MON.plusDays(8).toString(),
            strength = listOf(Triple("ex1", 10, 60.0), Triple("ex3", 5, 200.0)))
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(prev, curr), emptyMap())
        val d = h.days[8]
        // ex1 only: 600 vs 500 -> +20%
        assertEquals(20.0, d.tonnageChangePct!!, 1e-9)
        assertEquals(DayCellStatus.IMPROVED, d.status)
    }

    @Test
    fun `cardio minutes shown when there is no tonnage percent`() {
        // No previous session -> no %, so cardio minutes fill the slot.
        val s = session(
            "c", "rt-cardio", MON.plusDays(3).toString(),
            cardioBlocks = listOf("2026-07-30T09:00:00" to "2026-07-30T09:25:30"), // 25 min (floor)
        )
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(s), emptyMap())
        val d = h.days[3]
        assertNull(d.tonnageChangePct)
        assertEquals(25, d.cardioMinutes)
        assertEquals(DayCellStatus.IMPROVED, d.status) // first session
    }

    @Test
    fun `non-completed sessions are ignored`() {
        val open = session("o", "rt-a", MON.plusDays(2).toString(), completedAt = "",
            strength = listOf(Triple("ex1", 10, 50.0)))
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(open), emptyMap())
        assertEquals(DayCellStatus.NONE, h.days[2].status)
        assertNull(h.days[2].sessionId)
    }

    @Test
    fun `latest session of a day wins`() {
        val early = session("e", "rt-a", MON.plusDays(4).toString(), completedAt = "2026-07-31T08:00:00",
            routineName = "MORNING", strength = listOf(Triple("ex1", 5, 50.0)))
        val late = session("l", "rt-b", MON.plusDays(4).toString(), completedAt = "2026-07-31T19:00:00",
            routineName = "EVENING", strength = listOf(Triple("ex2", 5, 50.0)))
        val h = GitgraphHistoryCalculator.buildWindow(MON, listOf(early, late), emptyMap())
        assertEquals("l", h.days[4].sessionId)
        assertEquals("EVENING", h.days[4].routineName)
    }

    @Test
    fun `dayCell is reusable for a single session (parity with MainViewModel current-week row)`() {
        val prev = session("p", "rt-a", MON.plusDays(1).toString(), strength = listOf(Triple("ex1", 10, 50.0)))
        val curr = session("c", "rt-a", MON.plusDays(8).toString(), strength = listOf(Triple("ex1", 11, 50.0)))
        val cell = GitgraphHistoryCalculator.dayCell(curr, mapOf("rt-a" to listOf(prev, curr)))
        assertEquals(DayCellStatus.IMPROVED, cell.status)
        assertEquals(10.0, cell.tonnageChangePct!!, 1e-9)
    }
}
