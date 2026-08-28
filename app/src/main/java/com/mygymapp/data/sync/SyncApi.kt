package com.mygymapp.data.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncResult {
    /**
     * [status] is the server's own word for what it did — `stored` (first time) or
     * `duplicate` (idempotent re-receipt), see `docs/SYNC.md` §2.2. Both mean the server
     * has the content: the ledger only flips to SENT on a confirmed 2xx, so this is a
     * genuine delivery receipt, not a fire-and-forget.
     *
     * [bytesSent] / [durationMs] describe the upload just performed (the raw file byte
     * count and the wall-clock time of the HTTP call) — surfaced on the end-of-session
     * summary. Zero on results that didn't move a payload (e.g. [checkHealth]).
     */
    data class Success(
        val status: String,
        val bytesSent: Long = 0,
        val durationMs: Long = 0,
    ) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

/**
 * OkHttp client for `POST /v1/sessions` — see `docs/SYNC.md` §1.4. Sends the raw session
 * file bytes unmodified as a multipart part alongside a small JSON envelope; never
 * re-encodes the session content, so this class has zero knowledge of the YAML schema.
 */
@Singleton
class SyncApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Posts [file] (the raw session `.md` bytes, unchanged) to [serverUrl]`/v1/sessions`.
     * [contentHash] must already include the `sha256:` prefix (see [SyncLedgerRepository.hashOf]).
     * Never throws — network/parse failures come back as [SyncResult.Failure].
     */
    fun postSession(
        serverUrl: String,
        bearerToken: String,
        sessionId: String,
        relPath: String,
        contentHash: String,
        appVersion: String,
        file: File,
    ): SyncResult {
        val envelope = JSONObject().apply {
            put("sessionId", sessionId)
            put("relPath", relPath)
            put("contentHash", contentHash)
            put("appVersion", appVersion)
            put("clientSentAt", Instant.now().toString())
        }.toString()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(
                MultipartBody.Part.createFormData(
                    "envelope",
                    null,
                    envelope.toRequestBody("application/json".toMediaType()),
                )
            )
            .addPart(
                MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.asRequestBody("text/markdown".toMediaType()),
                )
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/v1/sessions")
            .header("Authorization", "Bearer $bearerToken")
            .post(body)
            .build()

        val bytesSent = file.length()
        val startedAt = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startedAt
                if (response.isSuccessful) {
                    val status = runCatching {
                        JSONObject(response.body?.string().orEmpty()).optString("status", "stored")
                    }.getOrDefault("stored")
                    SyncResult.Success(status, bytesSent = bytesSent, durationMs = durationMs)
                } else {
                    SyncResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
            }
        } catch (e: Exception) {
            SyncResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `GET /health` — used by the Options screen to verify reachability without a real payload. */
    fun checkHealth(serverUrl: String): SyncResult {
        if (serverUrl.isBlank()) return SyncResult.Failure("URL vuoto")
        val request = Request.Builder().url("$serverUrl/health").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    SyncResult.Success(response.code.toString())
                } else {
                    SyncResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
            }
        } catch (e: Exception) {
            SyncResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
