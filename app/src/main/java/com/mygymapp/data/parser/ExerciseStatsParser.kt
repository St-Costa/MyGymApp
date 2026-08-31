package com.mygymapp.data.parser

import com.mygymapp.data.model.ContextStats
import com.mygymapp.data.model.ExerciseStats
import com.mygymapp.data.model.PreviousSet
import com.mygymapp.data.model.SlotContext

/**
 * Round-trips [ExerciseStats] to/from the YAML sidecar at `history/_stats/{exerciseId}.yaml`.
 * Uses the shared [MarkdownParser] front-matter (de)serializer — the file is front-matter
 * only, no body.
 *
 * Layout:
 * ```
 * ---
 * exerciseId: "ex-3e4195a9"
 * schemaVersion: 1
 * contexts:
 *   - context: "NORMAL"
 *     previousSessionDate: "2026-08-25T19:04:11"
 *     hasPriorRealTonnage: true
 *     pr:
 *       - reps: 6
 *         weight: 90.0
 *     previousSets:
 *       - reps: 8
 *         weight: 80.0
 *       - reps: 6
 *         weight: 82.5
 * ---
 * ```
 * `bwBaseWeightKg` and `isBodyweight: true` are added to a set entry only for bodyweight sets
 * (body weight at the time the set was logged, and the marker itself); non-bodyweight sidecars
 * keep the two-key `reps`/`weight` shape. `previousSetsBodyweight` / `previousSessionDateBw`
 * mirror `previousSets` / `previousSessionDate` for the bodyweight approach (schema v4).
 * `pr` (best tonnage) and `rmPr` (best estimated 1RM) are each modelled as a 0-or-1 element
 * list (not a bare nested map): they reuse the exact same `setMap` shape as `previousSets`, so
 * all go through one well-tested serializer path and the reader is a single
 * `parseSetList(...).firstOrNull()`.
 */
object ExerciseStatsParser {

    fun toYaml(stats: ExerciseStats): String {
        val contexts = stats.perContext.entries
            .sortedBy { it.key.ordinal }
            .map { (ctx, cs) ->
                val m = linkedMapOf<String, Any?>(
                    "context" to ctx.name,
                    "previousSessionDate" to cs.previousSessionDate,
                    "hasPriorRealTonnage" to cs.hasPriorRealTonnage,
                )
                cs.pr?.let { m["pr"] = listOf(setMap(it)) }
                cs.rmPr?.let { m["rmPr"] = listOf(setMap(it)) }
                if (cs.previousSets.isNotEmpty()) {
                    m["previousSets"] = cs.previousSets.map { setMap(it) }
                }
                if (cs.previousSetsBodyweight.isNotEmpty()) {
                    m["previousSessionDateBodyweight"] = cs.previousSessionDateBodyweight
                    m["previousSetsBodyweight"] = cs.previousSetsBodyweight.map { setMap(it) }
                }
                m
            }

        val fm = linkedMapOf<String, Any?>(
            "exerciseId" to stats.exerciseId,
            "schemaVersion" to stats.schemaVersion,
            "contexts" to contexts,
        )
        return MarkdownParser.serialize(fm, body = "")
    }

    /**
     * Parses a sidecar. Returns null if the content is unusable (empty, no exerciseId).
     * A schema-version mismatch is **not** handled here — the caller compares
     * [ExerciseStats.schemaVersion] against [ExerciseStats.SCHEMA_VERSION] and decides to
     * rebuild — so an old-schema file still parses into a well-formed object.
     */
    @Suppress("UNCHECKED_CAST")
    fun fromYaml(content: String): ExerciseStats? {
        val fm = MarkdownParser.parse(content).frontmatter
        val exerciseId = fm["exerciseId"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val schemaVersion = (fm["schemaVersion"] as? Number)?.toInt() ?: 0

        val rawContexts = fm["contexts"] as? List<*> ?: emptyList<Any?>()
        val perContext = rawContexts.mapNotNull { raw ->
            val map = raw as? Map<String, Any?> ?: return@mapNotNull null
            val ctx = (map["context"] as? String)?.let { name ->
                SlotContext.entries.firstOrNull { it.name == name }
            } ?: return@mapNotNull null
            val cs = ContextStats(
                previousSets = parseSetList(map["previousSets"]),
                previousSessionDate = map["previousSessionDate"]?.toString() ?: "",
                previousSetsBodyweight = parseSetList(map["previousSetsBodyweight"]),
                previousSessionDateBodyweight = map["previousSessionDateBodyweight"]?.toString() ?: "",
                pr = parseSetList(map["pr"]).firstOrNull(),
                rmPr = parseSetList(map["rmPr"]).firstOrNull(),
                hasPriorRealTonnage = map["hasPriorRealTonnage"] as? Boolean ?: false,
            )
            ctx to cs
        }.toMap()

        return ExerciseStats(
            exerciseId = exerciseId,
            schemaVersion = schemaVersion,
            perContext = perContext,
        )
    }

    private fun setMap(s: PreviousSet): Map<String, Any?> =
        linkedMapOf<String, Any?>("reps" to s.reps, "weight" to s.weight).apply {
            // Only bodyweight sets carry a base body weight / marker — omit the keys otherwise
            // so non-bodyweight sidecars keep their existing two-key shape.
            if (s.bwBaseWeightKg > 0.0) put("bwBaseWeightKg", s.bwBaseWeightKg)
            if (s.isBodyweight) put("isBodyweight", true)
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseSetList(raw: Any?): List<PreviousSet> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            PreviousSet(
                reps = (m["reps"] as? Number)?.toInt() ?: 0,
                weight = (m["weight"] as? Number)?.toDouble() ?: 0.0,
                bwBaseWeightKg = (m["bwBaseWeightKg"] as? Number)?.toDouble() ?: 0.0,
                isBodyweight = m["isBodyweight"] as? Boolean ?: false,
            )
        }
    }
}
