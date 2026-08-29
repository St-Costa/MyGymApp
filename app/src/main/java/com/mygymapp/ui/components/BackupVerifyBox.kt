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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mygymapp.data.sync.BackupDiffEntry
import com.mygymapp.data.sync.BackupError
import com.mygymapp.data.sync.BackupErrorCategory
import com.mygymapp.data.sync.BackupVerifyReport
import com.mygymapp.ui.theme.JetBrainsMono

private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)

/**
 * git-diffstat-style rendering of a full-store backup round-trip (docs/BACKUP.md §3.7).
 *
 * Header "Backup sul server" — centred, `titleMedium`, with a server icon on the left.
 * Sections **Esercizi** / **Routine** / **Sessioni** (`titleSmall`, bold) each carry a
 * `(X/Y allineate)` suffix in the header colour, turning red when not all aligned. Each
 * changed item is `<name>  -++` in JetBrains Mono at `bodyMedium` — name in the normal text
 * colour, only the `-`/`+` runs coloured. "ERRORI" (raw filenames) shows only on failure.
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Backup sul server",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))

        when {
            running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Verifica in corso…", style = MaterialTheme.typography.bodyMedium)
            }
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = RED,
                fontFamily = JetBrainsMono,
            )
            report != null -> ReportBody(report)
            else -> Text(
                "In attesa…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportBody(r: BackupVerifyReport) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RecordSection(
            title = "Esercizi",
            local = r.exercisesLocal,
            matching = r.exercisesMatching,
            diffs = r.exercises,
            errors = r.errorsOf(BackupErrorCategory.EXERCISE),
        )

        Spacer(Modifier.height(8.dp))
        RecordSection(
            title = "Routine",
            local = r.routinesLocal,
            matching = r.routinesMatching,
            diffs = r.routines,
            errors = r.errorsOf(BackupErrorCategory.ROUTINE),
        )

        Spacer(Modifier.height(8.dp))
        RecordSection(
            title = "Sessioni",
            local = r.sessionsLocal,
            matching = r.sessionsMatching,
            diffs = emptyList(),
            errors = r.errorsOf(BackupErrorCategory.SESSION),
            extraRows = r.sessionsChanged.takeIf { it.isNotEmpty() }
                ?.let { listOf("da inviare: " + it.joinToString(", ")) }.orEmpty(),
        )
    }
}

/** One record-type block: header `Titolo  (X/Y allineate · N errori)` (red when off), then
 *  the diff rows, then any error rows for this category (raw filename → reason, all red). */
@Composable
private fun RecordSection(
    title: String,
    local: Int,
    matching: Int,
    diffs: List<BackupDiffEntry>,
    errors: List<BackupError>,
    extraRows: List<String> = emptyList(),
) {
    val base = MaterialTheme.colorScheme.onSurface
    val off = matching != local || errors.isNotEmpty()
    val count = buildString {
        append("$matching/$local allineat${plural(local)}")
        if (errors.isNotEmpty()) append(" · ${errors.size} error${if (errors.size == 1) "e" else "i"}")
    }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (off) RED else base, fontWeight = FontWeight.Bold)) { append(title) }
            withStyle(SpanStyle(color = if (off) RED else base, fontWeight = FontWeight.Normal)) { append("  ($count)") }
        },
        style = MaterialTheme.typography.titleSmall,
    )
    diffs.forEach { DiffRow(it) }
    extraRows.forEach { FileRow(it, RED) }
    errors.forEach { e -> FileRow("${e.fileName} → ${e.detail}", RED) }
}

/** "o" for exactly one, "e" otherwise (allineato / allineate). */
private fun plural(n: Int) = if (n == 1) "o" else "e"

/** One `<name>  -++` diff row in JetBrains Mono — name normal colour, `-` red, `+` green. */
@Composable
private fun DiffRow(e: BackupDiffEntry) {
    val nameColor = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = nameColor)) { append("${e.displayName}  ") }
            withStyle(SpanStyle(color = RED)) { append("-".repeat(e.removed.coerceAtMost(30))) }
            withStyle(SpanStyle(color = GREEN)) { append("+".repeat(e.added.coerceAtMost(30))) }
        },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = JetBrainsMono,
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
private fun FileRow(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = JetBrainsMono,
        color = color,
        modifier = Modifier.padding(start = 8.dp),
    )
}
