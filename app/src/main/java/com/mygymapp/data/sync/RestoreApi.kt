package com.mygymapp.data.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One entry from `GET /v1/manifest`: a server-side file and the sha256 of its bytes. */
data class ManifestEntry(val relPath: String, val contentHash: String)

sealed class RestoreResult {
    data class Manifest(val entries: List<ManifestEntry>) : RestoreResult()
    data class FileBytes(val relPath: String, val bytes: ByteArray) : RestoreResult() {
        override fun equals(other: Any?) =
            other is FileBytes && other.relPath == relPath && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * relPath.hashCode() + bytes.contentHashCode()
    }
    data class Failure(val reason: String) : RestoreResult()
}

/**
 * Read side of the full-store backup (`docs/BACKUP.md` §3.6): `GET /v1/manifest` lists every
 * live file on the server with its content hash, `GET /v1/file?relPath=…` pulls one file's
 * raw bytes. The phone diffs the manifest against its local ledger and pulls only what's
 * missing or hash-mismatched — a `git fetch` in spirit, pull-only, never deleting.
 *
 * Covers **all** record types on the server (sessions, readiness, scale, ecg, exercises,
 * routines), not just repo files — a fresh install can rebuild its whole `history/` from here.
 */
@Singleton
class RestoreApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun fetchManifest(serverUrl: String, bearerToken: String): RestoreResult {
        val request = Request.Builder()
            .url("$serverUrl/v1/manifest")
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RestoreResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val files = json.optJSONObject("files") ?: JSONObject()
                val entries = files.keys().asSequence().map { key ->
                    ManifestEntry(relPath = key, contentHash = files.getString(key))
                }.toList()
                RestoreResult.Manifest(entries)
            }
        } catch (e: Exception) {
            RestoreResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    fun fetchFile(serverUrl: String, bearerToken: String, relPath: String): RestoreResult {
        val httpUrl = (serverUrl.toHttpUrlOrNull() ?: return RestoreResult.Failure("URL non valido"))
            .newBuilder()
            .addPathSegments("v1/file")
            .addQueryParameter("relPath", relPath)
            .build()
        val request = Request.Builder()
            .url(httpUrl)
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RestoreResult.Failure("HTTP ${response.code} per $relPath")
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                RestoreResult.FileBytes(relPath, bytes)
            }
        } catch (e: Exception) {
            RestoreResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
