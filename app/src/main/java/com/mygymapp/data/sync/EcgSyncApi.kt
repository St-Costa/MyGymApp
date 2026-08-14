package com.mygymapp.data.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp client for `POST /v1/ecg` — the raw-ECG counterpart of [SyncApi] /
 * [ReadinessSyncApi] / [ScaleWeighInSyncApi]. Same dedicated-class-per-record-type
 * reasoning (docs/SYNC.md "Extensibility").
 *
 * Two differences from the other three APIs:
 *  - The payload is gzip-compressed binary (`application/gzip`), not markdown/YAML text —
 *    [EcgSyncWorker] compresses before calling [postEcg].
 *  - Longer timeouts: a compressed ~130Hz Int16 ECG stream is meaningfully larger than a
 *    session/readiness/weigh-in YAML file, even after compression.
 */
@Singleton
class EcgSyncApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Posts [compressedBytes] (gzip-compressed raw `.ecg` file) to [serverUrl]`/v1/ecg`.
     * [contentHash] must already include the `sha256:` prefix and must be computed over
     * [compressedBytes] (see [EcgSyncLedgerRepository.hashOf]) — the server verifies
     * against the compressed bytes it receives before decompressing.
     */
    fun postEcg(
        serverUrl: String,
        bearerToken: String,
        sessionId: String,
        relPath: String,
        contentHash: String,
        appVersion: String,
        fileName: String,
        compressedBytes: ByteArray,
    ): SyncResult {
        val envelope = JSONObject().apply {
            put("sessionId", sessionId)
            put("relPath", relPath)
            put("contentHash", contentHash)
            put("encoding", "gzip")
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
                    fileName,
                    compressedBytes.toRequestBody("application/gzip".toMediaType()),
                )
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/v1/ecg")
            .header("Authorization", "Bearer $bearerToken")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val status = runCatching {
                        JSONObject(response.body?.string().orEmpty()).optString("status", "stored")
                    }.getOrDefault("stored")
                    SyncResult.Success(status)
                } else {
                    SyncResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
            }
        } catch (e: Exception) {
            SyncResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
