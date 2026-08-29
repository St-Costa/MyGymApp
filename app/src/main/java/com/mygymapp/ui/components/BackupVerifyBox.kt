package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mygymapp.data.sync.BackupVerifyReport

/**
 * git-diff-style rendering of a full-store backup round-trip (docs/BACKUP.md §3.7): per-
 * category `+` (pushed) / `-` (problem) lines, then a one-line paraphrase of the server's
 * response. Shared by Options ("Verifica backup sul server"), the end-of-session summary,
 * and the debug preview screen.
 *
 * Pass exactly one of [running] / [error] / [report] as the meaningful state.
 */
@Composable
fun BackupVerifyBox(
    running: Boolean = false,
    error: String? = null,
    report: BackupVerifyReport? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Backup schede/esercizi",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        when {
            running -> Row2 {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Verifica in corso…", style = mono())
            }
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            report != null -> ReportBody(report)
            else -> Text(
                "In attesa…",
                style = mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportBody(report: BackupVerifyReport) {
    val green = Color(0xFF3FB950)
    val red = Color(0xFFF85149)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Category("esercizi", report.exercisesPushed, report.exercisesUnchanged, green)
        Spacer(Modifier.height(4.dp))
        Category("routine", report.routinesPushed, report.routinesUnchanged, green)

        val problems = report.pushFailed +
            report.missingAfter.map { "$it (mancante sul server)" } +
            report.hashMismatch.map { "$it (hash diverso)" } +
            report.readBackMismatch.map { "$it (rilettura non identica)" }
        if (problems.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            problems.forEach { Text("- $it", style = mono(), color = red) }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            "Risposta server: ${report.serverSummary}",
            style = MaterialTheme.typography.bodySmall,
            color = if (report.allGood) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Category(title: String, pushed: List<String>, unchanged: Int, plusColor: Color) {
    Text(
        "$title  (${pushed.size} inviati, $unchanged invariati)",
        style = mono().copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (pushed.isEmpty()) {
        Text("  (niente da inviare)", style = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        pushed.forEach { Text("+ $it", style = mono(), color = plusColor) }
    }
}

@Composable
private fun mono() =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) { content() }
}
