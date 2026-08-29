package com.mygymapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mygymapp.data.sync.BackupDiffEntry
import com.mygymapp.data.sync.BackupVerifyReport

private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)

/**
 * git-diffstat-style rendering of a full-store backup round-trip (docs/BACKUP.md §3.7).
 * Sections "Esercizi" / "Routine" list each pushed item as `<name>  -+++` (name in the
 * normal text colour, only the `+`/`-` coloured). "Sessioni" is a count line. An "ERRORI"
 * section (raw filenames + reason) appears only when something failed.
 *
 * Pass exactly one meaningful state: [running] / [error] / [report].
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
            "Backup sul server",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        when {
            running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Verifica in corso…", style = mono())
            }
            error != null -> Text(error, style = mono(), color = RED)
            report != null -> ReportBody(report)
            else -> Text("In attesa…", style = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportBody(r: BackupVerifyReport) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Section("Esercizi", r.exercises, r.exercisesUnchanged)
        Spacer(Modifier.height(6.dp))
        Section("Routine", r.routines, r.routinesUnchanged)
        Spacer(Modifier.height(6.dp))

        Text("Sessioni", style = mono().copy(fontWeight = FontWeight.Bold))
        Text(
            "  ${r.sessionsMatching}/${r.sessionsLocal} allineate" +
                if (r.sessionsChanged.isEmpty()) "" else " · da inviare: ${r.sessionsChanged.joinToString(", ")}",
            style = mono(),
            color = if (r.sessionsMatching == r.sessionsLocal) MaterialTheme.colorScheme.onSurfaceVariant else RED,
        )

        if (r.errors.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("ERRORI", style = mono().copy(fontWeight = FontWeight.Bold), color = RED)
            r.errors.forEach { e ->
                Text("  ${e.fileName} → ${e.detail}", style = mono(), color = RED)
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            "Manifest: ${r.manifestServerFiles}/${r.manifestLocalFiles} coincidono",
            style = mono(),
            color = if (r.allGood) MaterialTheme.colorScheme.onSurfaceVariant else RED,
        )
    }
}

@Composable
private fun Section(title: String, entries: List<BackupDiffEntry>, unchanged: Int) {
    Text(title, style = mono().copy(fontWeight = FontWeight.Bold))
    if (entries.isEmpty()) {
        Text("  nessuna modifica", style = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        entries.forEach { e -> DiffLine(e) }
    }
    if (unchanged > 0) {
        Text("  … $unchanged invariat${if (unchanged == 1) "o" else "i"}", style = mono(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** `calf raise  -+++` — name in normal colour, `-` in red then `+` in green (git order). */
@Composable
private fun DiffLine(e: BackupDiffEntry) {
    val nameColor = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = nameColor)) { append("  ${e.displayName}  ") }
            withStyle(SpanStyle(color = RED)) { append("-".repeat(e.removed.coerceAtMost(20))) }
            withStyle(SpanStyle(color = GREEN)) { append("+".repeat(e.added.coerceAtMost(20))) }
        },
        style = mono(),
    )
}

@Composable
private fun mono() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
