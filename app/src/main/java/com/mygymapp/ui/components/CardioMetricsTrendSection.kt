package com.mygymapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mygymapp.data.repository.CardioMetricsTrendReport
import com.mygymapp.data.repository.averageWeeklyDelta
import com.mygymapp.ui.theme.SignalGreen
import com.mygymapp.ui.theme.SignalOrange
import com.mygymapp.ui.theme.TrendBlue

private val RestingHrColor = TrendBlue
private val Hrr60sColor = SignalGreen
private val Vo2maxColor = SignalOrange
private val GoodDirectionColor = SignalGreen

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
                    "Weekly median of recorded sessions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.restingHrWeekly.map { it.value },
                    color = RestingHrColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    xLabels = report.restingHrWeekly.map { it.weekLabel },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }

        if (report.hrr60sWeekly.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTitle("HRR 60s (bpm) - 2 mesi", goodDirectionDown = false)
                    report.hrr60sWeekly.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                Text(
                    "Weekly median of 60s post-peak HR recovery",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.hrr60sWeekly.map { it.value },
                    color = Hrr60sColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    xLabels = report.hrr60sWeekly.map { it.weekLabel },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
            }
        }

        if (report.vo2maxMonthly.isNotEmpty()) {
            ChartCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricTitle("VO2max (ml/kg/min) - 6 mesi", goodDirectionDown = false)
                    report.vo2maxMonthly.averageWeeklyDelta()?.let { delta -> WeeklyDeltaLabel(delta) }
                }
                Text(
                    "Median over 4-week windows",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PointLineChart(
                    values = report.vo2maxMonthly.map { it.value },
                    color = Vo2maxColor,
                    labelStyle = PointLabelStyle.TEXT_ABOVE,
                    xLabels = report.vo2maxMonthly.map { it.monthLabel },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
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
