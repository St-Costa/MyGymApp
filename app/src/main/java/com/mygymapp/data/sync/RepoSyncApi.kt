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

/**
 * OkHttp client for `POST /v1/repo` — the fifth record type (exercises + routines, see
 * `docs/BACKUP.md` §3.5). Sends the raw `.md` bytes unmodified on an `op: "upsert"`, and
 * an envelope-only tombstone on an `op: "delete"`. Same dedicated-class-per-record-type
 * reasoning as [SyncApi] / [ReadinessSyncApi] / [ScaleWeighInSyncApi].
 *
 * `stored` / `duplicate` / `deleted` / `already_absent` are **all** success — retries stay
 * safe (idempotent receiver, `docs/BACKUP.md` §3.5).
 */
@Singleton
class RepoSyncApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * `op: "upsert"` — posts [file]'s raw bytes to [serverUrl]`/v1/repo`. [contentHash] must
     * already carry the `sha256:` prefix. Never throws.
     */
    fun postUpsert(
        serverUrl: String,
        bearerToken: String,
        relPath: String,
        contentHash: String,
        appVersion: String,
        file: File,
    ): SyncResult = post(serverUrl, bearerToken, relPath, "upsert", contentHash, appVersion, file)

    /**
     * `op: "delete"` — envelope only, no file part. [lastKnownHash] (the hash of the content
     * last successfully synced for this path) rides along so the server can confirm which
     * version it's tombstoning. Never throws.
     */
    fun postDelete(
        serverUrl: String,
        bearerToken: String,
        relPath: String,
        lastKnownHash: String,
        appVersion: String,
    ): SyncResult = post(serverUrl, bearerToken, relPath, "delete", lastKnownHash, appVersion, null)

    private fun post(
        serverUrl: String,
        bearerToken: String,
        relPath: String,
        op: String,
        contentHash: String,
        appVersion: String,
        file: File?,
    ): SyncResult {
        val envelope = JSONObject().apply {
            put("relPath", relPath)
            put("op", op)
            put("contentHash", contentHash)
            put("appVersion", appVersion)
            put("clientSentAt", Instant.now().toString())
        }.toString()

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(
                MultipartBody.Part.createFormData(
                    "envelope",
                    null,
                    envelope.toRequestBody("application/json".toMediaType()),
                )
            )
        if (op == "upsert" && file != null) {
            bodyBuilder.addPart(
                MultipartBody.Part.createFormData(
                    "file",
                    file.name,
                    file.asRequestBody("text/markdown".toMediaType()),
                )
            )
        }

        val request = Request.Builder()
            .url("$serverUrl/v1/repo")
            .header("Authorization", "Bearer $bearerToken")
            .post(bodyBuilder.build())
            .build()

        val bytesSent = file?.length() ?: 0L
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
}
