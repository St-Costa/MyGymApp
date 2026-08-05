package com.mygymapp.data.sync

import com.mygymapp.data.model.WorkoutSession
import com.mygymapp.data.parser.WorkoutParser
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One labeled step of the "Test sincronizzazione" run in the Options screen. */
data class DiagnosticStep(
    val label: String,
    val ok: Boolean,
    val detail: String,
)

data class DiagnosticRun(
    val steps: List<DiagnosticStep> = emptyList(),
    val running: Boolean = false,
)

/**
 * Runs the sync transport end-to-end from the Options screen debug button, logging every
 * step to [AppLogger] (`adb shell run-as com.mygymapp cat files/gymdata/logs/app.log`) so
 * connectivity/auth/parsing problems are visible without attaching a debugger. Mirrors the
 * exact request [SyncWorker] makes — this is not a separate code path, just [SyncApi]
 * called synchronously with results surfaced to the UI instead of the ledger.
 *
 * Steps:
 * 1. Config sanity (URL + token present).
 * 2. `GET /health`.
 * 3. `POST /v1/sessions` with a real session if one exists on disk, otherwise a synthetic
 *    one built in-memory (never written to `history/`, so it can't pollute real data or
 *    tonnage stats) — this means the button works even before the user has logged a
 *    single workout.
 * 4. Re-send of the exact same payload, to confirm the server's idempotency path answers
 *    `duplicate` rather than erroring or double-storing (docs/SYNC.md §2.2 step 4 / §3.3).
 */
@Singleton
class SyncDiagnostics @Inject constructor(
    private val config: SyncConfigRepository,
    private val api: SyncApi,
    private val workoutRepository: WorkoutRepository,
    private val ledger: SyncLedgerRepository,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "SyncDiagnostics"
    }

    suspend fun run(): List<DiagnosticStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStep>()

        fun log(step: DiagnosticStep) {
            steps.add(step)
            if (step.ok) appLogger.i(TAG, "${step.label}: ${step.detail}")
            else appLogger.w(TAG, "${step.label} FAILED: ${step.detail}")
        }

        appLogger.i(TAG, "=== Sync diagnostic run started ===")

        // Step 1 — config sanity
        val serverUrl = config.serverUrl()
        val token = config.bearerToken()
        if (serverUrl.isBlank() || token.isBlank()) {
            log(DiagnosticStep("Configurazione", false, "URL server o token mancanti"))
            appLogger.w(TAG, "=== Sync diagnostic run aborted: not configured ===")
            return@withContext steps
        }
        log(DiagnosticStep("Configurazione", true, "URL=$serverUrl, token=${token.take(6)}…"))

        // Step 2 — health check
        val healthy = try {
            api.checkHealth(serverUrl)
        } catch (e: Exception) {
            appLogger.e(TAG, "Health check threw", e)
            false
        }
        log(
            DiagnosticStep(
                "Health check (GET /health)",
                healthy,
                if (healthy) "raggiungibile" else "non raggiungibile — controlla Tailscale/URL",
            )
        )
        if (!healthy) {
            appLogger.w(TAG, "=== Sync diagnostic run aborted: server unreachable ===")
            return@withContext steps
        }

        // Step 3 — real or synthetic session, sent once
        val (session, file, isSynthetic) = resolveTestSession()
        log(
            DiagnosticStep(
                "Sessione di test",
                true,
                if (isSynthetic) "nessuna sessione reale trovata — generata sessione fittizia id=${session.id}"
                else "usando sessione reale id=${session.id} (${session.date})",
            )
        )

        val hash = ledger.hashOf(file)
        val relPath = if (isSynthetic) "diagnostic/${file.name}" else workoutRepository.relPathFor(session)

        val firstResult = api.postSession(
            serverUrl = serverUrl,
            bearerToken = token,
            sessionId = session.id,
            relPath = relPath,
            contentHash = hash,
            appVersion = "diagnostic",
            file = file,
        )
        log(
            when (firstResult) {
                is SyncResult.Success -> DiagnosticStep("Invio sessione (POST /v1/sessions)", true, "risposta: ${firstResult.status}")
                is SyncResult.Failure -> DiagnosticStep("Invio sessione (POST /v1/sessions)", false, firstResult.reason)
            }
        )

        // Step 4 — re-send same content, expect "duplicate" (idempotency check)
        if (firstResult is SyncResult.Success) {
            val secondResult = api.postSession(
                serverUrl = serverUrl,
                bearerToken = token,
                sessionId = session.id,
                relPath = relPath,
                contentHash = hash,
                appVersion = "diagnostic",
                file = file,
            )
            log(
                when (secondResult) {
                    is SyncResult.Success -> DiagnosticStep(
                        "Verifica idempotenza (doppio invio)",
                        secondResult.status == "duplicate",
                        "risposta: ${secondResult.status}" +
                            if (secondResult.status != "duplicate") " (atteso 'duplicate' — controlla il controllo hash lato server)" else "",
                    )
                    is SyncResult.Failure -> DiagnosticStep("Verifica idempotenza (doppio invio)", false, secondResult.reason)
                }
            )
        }

        if (isSynthetic) file.delete()

        appLogger.i(TAG, "=== Sync diagnostic run finished ===")
        steps
    }

    /**
     * Picks the most recently completed real session if one exists; otherwise builds a
     * minimal synthetic session + writes it to a throwaway temp file (never touching
     * `history/`, so `WorkoutRepository`'s own state is untouched by a diagnostic run).
     */
    private suspend fun resolveTestSession(): Triple<WorkoutSession, File, Boolean> {
        val real = workoutRepository.getAllCompletedSessions()
            .maxByOrNull { it.completedAt }
        if (real != null) {
            val file = workoutRepository.fileFor(real)
            if (file.exists()) return Triple(real, file, false)
        }

        val synthetic = WorkoutSession(
            id = "diag" + UUID.randomUUID().toString().take(4),
            routineId = "rt-diagnost",
            routineName = "Diagnostic",
            date = LocalDate.now().toString(),
            completedAt = java.time.LocalDateTime.now().toString(),
            totalTonnage = 0.0,
            notes = "Sessione generata da 'Test sincronizzazione' (Opzioni) — non reale, sicura da ignorare/cancellare lato server.",
        )
        val tempFile = File.createTempFile("sync_diag_", ".md")
        tempFile.writeText(WorkoutParser.toMarkdown(synthetic))
        return Triple(synthetic, tempFile, true)
    }
}
