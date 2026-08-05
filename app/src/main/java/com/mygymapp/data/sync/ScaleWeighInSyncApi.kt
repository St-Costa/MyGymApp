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
 * OkHttp client for `POST /v1/scale-weighins` — the weigh-in counterpart of [SyncApi] /
 * [ReadinessSyncApi]. Same dedicated-class-per-record-type reasoning (docs/SYNC.md
 * "Extensibility").
 */
@Singleton
class ScaleWeighInSyncApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun postWeighIn(
        serverUrl: String,
        bearerToken: String,
        weighInId: String,
        relPath: String,
        contentHash: String,
        appVersion: String,
        file: File,
    ): SyncResult {
        val envelope = JSONObject().apply {
            put("weighInId", weighInId)
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
            .url("$serverUrl/v1/scale-weighins")
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
