package com.mygymapp.ui.screen.logs

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import java.io.File

/**
 * Options → Debug → LOGS & CRASHES. Crash/ANR reports (newest first) and `app.log`, readable
 * on the phone and shareable as files — no adb needed. See
 * docs/CONVENTIONS.md#crash-reports--action-trail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val open = uiState.open

    BackHandler(enabled = open != null) { viewModel.close() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        open?.file?.name ?: "Logs & crashes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (open != null) viewModel.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (open != null) {
                        IconButton(onClick = { shareFile(context, open.file) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                open != null -> FileContent(uiState.content)
                uiState.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> FileList(uiState.files, onOpen = viewModel::open)
            }
        }
    }
}

@Composable
private fun FileList(files: List<LogFileUi>, onOpen: (LogFileUi) -> Unit) {
    val reports = files.count { it.kind != "log" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                if (reports == 0) "No crash reports. The event log is below."
                else "$reports report(s). Tap one to read it, then share it from the top bar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(files, key = { it.file.absolutePath }) { f ->
            val isCrash = f.kind != "log"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(f) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isCrash) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        f.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isCrash) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        f.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCrash) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileContent(content: String?) {
    if (content == null) {
        Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    // Both scroll directions: stack-trace lines are long and wrapping them makes frames
    // unreadable on a phone.
    SelectionContainer {
        Text(
            content,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            softWrap = false,
        )
    }
}

/** Shares the file itself (not its text — app.log can exceed the Binder transaction limit). */
private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "MyGymApp ${file.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}
