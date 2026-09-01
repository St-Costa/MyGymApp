package com.mygymapp.data.parser

import com.mygymapp.data.model.DayCellStatus
import com.mygymapp.data.model.GitgraphDay
import com.mygymapp.data.model.GitgraphHistory

/**
 * Round-trips [GitgraphHistory] to/from `history/_gitgraph.yaml` (front-matter only, no body).
 *
 * ```
 * ---
 * schemaVersion: 1
 * windowStartMonday: "2026-07-27"
 * days:
 *   - date: "2026-07-27"
 *     status: "IMPROVED"
 *     tonnageChangePct: 3.4
 *     routineName: "PUSH"
 *     sessionId: "abc12345"
 *   - date: "2026-07-28"
 *     status: "NONE"
 *   ...            # exactly 28 entries
 * ---
 * ```
 * Null/absent fields (a NONE day carries only `date` + `status`) are simply omitted.
 */
object GitgraphHistoryParser {

    fun toYaml(history: GitgraphHistory): String {
        val dayMaps = history.days.map { d ->
            val m = linkedMapOf<String, Any?>(
                "date" to d.date,
                "status" to d.status.name,
            )
            d.tonnageChangePct?.let { m["tonnageChangePct"] = it }
            d.stretchMinutes?.let { m["stretchMinutes"] = it }
            d.cardioMinutes?.let { m["cardioMinutes"] = it }
            d.routineName?.let { m["routineName"] = it }
            d.sessionId?.let { m["sessionId"] = it }
            m
        }
        val fm = linkedMapOf<String, Any?>(
            "schemaVersion" to history.schemaVersion,
            "windowStartMonday" to history.windowStartMonday,
            "days" to dayMaps,
        )
        return MarkdownParser.serialize(fm, body = "")
    }

    /** Parses the cache. Returns null if unusable (empty / no windowStartMonday / no days). */
    @Suppress("UNCHECKED_CAST")
    fun fromYaml(content: String): GitgraphHistory? {
        val fm = MarkdownParser.parse(content).frontmatter
        val windowStartMonday = fm["windowStartMonday"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val schemaVersion = (fm["schemaVersion"] as? Number)?.toInt() ?: 0

        val rawDays = fm["days"] as? List<*> ?: return null
        val days = rawDays.mapNotNull { raw ->
            val map = raw as? Map<String, Any?> ?: return@mapNotNull null
            val date = map["date"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GitgraphDay(
                date = date,
                status = (map["status"] as? String)
                    ?.let { name -> DayCellStatus.entries.firstOrNull { it.name == name } }
                    ?: DayCellStatus.NONE,
                tonnageChangePct = (map["tonnageChangePct"] as? Number)?.toDouble(),
                stretchMinutes = (map["stretchMinutes"] as? Number)?.toInt(),
                cardioMinutes = (map["cardioMinutes"] as? Number)?.toInt(),
                routineName = (map["routineName"] as? String)?.takeIf { it.isNotBlank() },
                sessionId = (map["sessionId"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
        if (days.isEmpty()) return null

        return GitgraphHistory(
            schemaVersion = schemaVersion,
            windowStartMonday = windowStartMonday,
            days = days,
        )
    }
}
