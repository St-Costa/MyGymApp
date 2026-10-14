package com.mygymapp.ui.screen.logs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mygymapp.data.util.AppLogger
import com.mygymapp.data.util.CrashReportFormat
import com.mygymapp.data.util.CrashReporter
import com.mygymapp.data.util.LogRetention
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class LogFileUi(
    val file: File,
    val title: String,
    val subtitle: String,
    /** `log` for app.log, otherwise the report kind (`crash`, `anr`, `native`, `oom`, `killed`). */
    val kind: String,
)

data class LogsUiState(
    val loading: Boolean = true,
    val files: List<LogFileUi> = emptyList(),
    val open: LogFileUi? = null,
    val content: String? = null,
)

/** Options → Debug → LOGS & CRASHES: lists `app.log` + crash reports, shows one, shares it. */
@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashReporter: CrashReporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { listFiles() }
            _uiState.value = _uiState.value.copy(loading = false, files = files)
        }
    }

    fun open(entry: LogFileUi) {
        _uiState.value = _uiState.value.copy(open = entry, content = null)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { read(entry) }
            if (_uiState.value.open == entry) _uiState.value = _uiState.value.copy(content = text)
        }
    }

    fun close() {
        _uiState.value = _uiState.value.copy(open = null, content = null)
    }

    private fun listFiles(): List<LogFileUi> {
        val log = AppLogger.logFile(context)
        val logUi = if (log.exists()) {
            listOf(LogFileUi(log, "app.log", "Event log · ${sizeLabel(log.length())} · newest first", "log"))
        } else emptyList()
        val reports = crashReporter.listReports().map { f ->
            val kind = CrashReportFormat.kindOf(f.name) ?: "report"
            val at = CrashReportFormat.timestampOf(f.name)
            LogFileUi(
                file = f,
                title = "${KIND_LABELS[kind] ?: kind.uppercase()} · ${at?.format(LIST_TS) ?: f.name}",
                subtitle = f.name,
                kind = kind,
            )
        }
        return reports + logUi
    }

    private fun read(entry: LogFileUi): String = try {
        if (entry.kind == "log") {
            LogRetention.newestFirst(entry.file.readLines(), MAX_LOG_ENTRIES).joinToString("\n")
                .ifEmpty { "(empty)" }
        } else {
            entry.file.readText()
        }
    } catch (t: Throwable) {
        "Could not read ${entry.file.name}: $t"
    }

    private fun sizeLabel(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

    companion object {
        /** The viewer shows the newest entries; share the file for the full history. */
        private const val MAX_LOG_ENTRIES = 1500
        private val LIST_TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        private val KIND_LABELS = mapOf(
            "crash" to "Crash",
            "anr" to "ANR (app froze)",
            "native" to "Native crash",
            "oom" to "Out of memory",
            "killed" to "Killed by system",
        )
    }
}
