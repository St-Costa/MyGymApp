package com.mygymapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.repository.CardioMetricsTrendReport
import com.mygymapp.data.repository.averageWeeklyDelta

private val RestingHrColor = Color(0xFF4FC3F7)
private val Hrr60sColor = Color(0xFF66BB6A)
private val Vo2maxColor = Color(0xFFFFA726)
private val GoodDirectionColor = Color(0xFF66BB6A)

@Composable
fun CardioMetricsTrendSection(report: CardioMetricsTrendReport) {
    if (!report.hasData) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("❤️", fontSize = 40.sp)
        }

        if (report.restingHrWeekly.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTitle("Resting HR (bpm) - 2 mesi", goodDirectionDown = true)
                    report.restingHrWeekly.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                Text(
                    "Mediana settimanale delle sessioni registrate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.restingHrWeekly.map { it.value },
                    color = RestingHrColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (point in report.restingHrWeekly) {
                        Text(point.weekLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (report.hrr60sWeekly.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTitle("HRR 60s (bpm) - 2 mesi", goodDirectionDown = false)
                    report.hrr60sWeekly.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                Text(
                    "Mediana settimanale del recupero HR a 60s dal picco",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.hrr60sWeekly.map { it.value },
                    color = Hrr60sColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (point in report.hrr60sWeekly) {
                        Text(point.weekLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (report.vo2maxMonthly.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTitle("VO2max (ml/kg/min) - 6 mesi", goodDirectionDown = false)
                    report.vo2maxMonthly.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                Text(
                    "Mediana su finestre di 4 settimane",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.vo2maxMonthly.map { it.value },
                    color = Vo2maxColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (point in report.vo2maxMonthly) {
                        Text(point.monthLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Title with a small arrow indicating which direction is good for this metric (↓ lower-is-better, ↑ higher-is-better). */
@Composable
private fun MetricTitle(text: String, goodDirectionDown: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            if (goodDirectionDown) " ↓" else " ↑",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = GoodDirectionColor,
        )
    }
}
