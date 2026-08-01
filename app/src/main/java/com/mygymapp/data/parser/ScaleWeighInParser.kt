package com.mygymapp.data.parser

import com.mygymapp.data.model.ScaleWeighIn

object ScaleWeighInParser {

    fun fromMarkdown(content: String): ScaleWeighIn {
        val doc = MarkdownParser.parse(content)
        val fm = doc.frontmatter
        return ScaleWeighIn(
            id = fm["id"]?.toString() ?: "",
            date = fm["date"]?.toString() ?: "",
            recordedAt = fm["recordedAt"]?.toString() ?: "",
            weightKg = (fm["weightKg"] as? Number)?.toDouble() ?: 0.0,
            bmi = (fm["bmi"] as? Number)?.toDouble() ?: 0.0,
            bodyFatPercent = (fm["bodyFatPercent"] as? Number)?.toDouble() ?: 0.0,
            leanMassPercent = (fm["leanMassPercent"] as? Number)?.toDouble() ?: 0.0,
        )
    }

    fun toMarkdown(weighIn: ScaleWeighIn): String {
        val frontmatter = linkedMapOf<String, Any?>(
            "id" to weighIn.id,
            "date" to weighIn.date,
            "recordedAt" to weighIn.recordedAt,
            "weightKg" to weighIn.weightKg,
        ).apply {
            if (weighIn.bmi > 0.0) put("bmi", weighIn.bmi)
            if (weighIn.bodyFatPercent > 0.0) put("bodyFatPercent", weighIn.bodyFatPercent)
            if (weighIn.leanMassPercent > 0.0) put("leanMassPercent", weighIn.leanMassPercent)
        }
        return MarkdownParser.serialize(frontmatter, "")
    }
}
