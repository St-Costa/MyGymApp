package com.mygymapp.data.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One entry for [RepoSyncApi.postBulk] — mirrors a [RepoLedgerEntry] pending row.
 * [file] is non-null iff [op] is `"upsert"` (its bytes go in a `file_<i>` part aligned to
 * this entry's index in the envelope array).
 */
data class RepoBulkEntry(
    val relPath: String,
    val op: String,
    val contentHash: String,
    val file: File?,
)

/** One `results[]` item from `POST /v1/repo/bulk`, in the same order as the request entries. */
data class RepoBulkResult(
    val relPath: String,
    /** `stored` / `duplicate` / `deleted` / `already_absent` / `error`. */
    val status: String,
    /** Non-null only when [status] is `"error"`. */
    val error: String?,
) {
    val isSuccess: Boolean get() = status != "error"
}

/** Outcome of one `POST /v1/repo/bulk` chunk. */
sealed class RepoBulkOutcome {
    /** The request returned `200`; [results] has one entry per request entry, in order. */
    data class Applied(val results: List<RepoBulkResult>) : RepoBulkOutcome()

    /** Whole-request failure (`401`, malformed envelope `400`/`422`, `413`, network). */
    data class Failure(val reason: String) : RepoBulkOutcome()
}

/**
 * OkHttp client for `POST /v1/repo` — the fifth record type (exercises + routines, see
 * `docs/BACKUP.md` §3.5). Sends the raw `.md` bytes unmodified on an `op: "upsert"`, and
 * an envelope-only tombstone on an `op: "delete"`. Same dedicated-class-per-record-type
 * reasoning as [SyncApi] / [ReadinessSyncApi] / [ScaleWeighInSyncApi].
 *
 * `stored` / `duplicate` / `deleted` / `already_absent` are **all** success — retries stay
 * safe (idempotent receiver, `docs/BACKUP.md` §3.5).
 *
 * [postBulk] collapses a whole ledger drain into one request + one server-side git commit
 * (`docs/backup/README.md` § "Batch endpoints"). The single-file [postUpsert]/[postDelete]
 * stay as the fallback.
 */
@Singleton
class RepoSyncApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Longer write budget — a bulk request aggregates up to 50 MB of file bytes. */
    private val bulkClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        /** Server's hard cap per `POST /v1/repo/bulk` request (`docs/backup/README.md`). */
        const val BULK_MAX_ENTRIES = 500
        const val BULK_MAX_FILE_BYTES = 50L * 1024 * 1024
    }

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

    /**
     * `POST /v1/repo/bulk` — one request for many upserts/deletes, one server-side git
     * commit for the whole batch. [entries] is auto-split into chunks of at most
     * [BULK_MAX_ENTRIES] / [BULK_MAX_FILE_BYTES]; the returned [RepoBulkOutcome.Applied]
     * concatenates every chunk's `results` in the original order.
     *
     * A per-entry problem (hash mismatch, bad path) comes back as that entry's
     * `status = "error"` inside `Applied` — the caller applies each result to the ledger
     * individually. Only a whole-request failure (`401`, malformed envelope, `413`,
     * network) yields [RepoBulkOutcome.Failure], and it fails only the offending chunk —
     * earlier chunks' results are still returned. Never throws.
     */
    fun postBulk(
        serverUrl: String,
        bearerToken: String,
        appVersion: String,
        entries: List<RepoBulkEntry>,
    ): RepoBulkOutcome {
        if (entries.isEmpty()) return RepoBulkOutcome.Applied(emptyList())

        val all = mutableListOf<RepoBulkResult>()
        for (chunk in chunkForBulk(entries)) {
            when (val r = postBulkChunk(serverUrl, bearerToken, appVersion, chunk)) {
                is RepoBulkOutcome.Applied -> all += r.results
                is RepoBulkOutcome.Failure ->
                    // Return what we have plus the failure. The worker treats any chunk
                    // failure as "retry the whole drain" — the already-applied results are
                    // idempotent on the next run (duplicate / already_absent).
                    return if (all.isEmpty()) r
                    else RepoBulkOutcome.Failure("${r.reason} (dopo ${all.size} già applicati)")
            }
        }
        return RepoBulkOutcome.Applied(all)
    }

    /** Split so no chunk exceeds the server's 500-entry / 50-MB-of-file-bytes cap. */
    internal fun chunkForBulk(entries: List<RepoBulkEntry>): List<List<RepoBulkEntry>> {
        val chunks = mutableListOf<List<RepoBulkEntry>>()
        var cur = mutableListOf<RepoBulkEntry>()
        var curBytes = 0L
        for (e in entries) {
            val size = e.file?.length() ?: 0L
            val wouldOverflow = cur.isNotEmpty() &&
                (cur.size >= BULK_MAX_ENTRIES || curBytes + size > BULK_MAX_FILE_BYTES)
            if (wouldOverflow) {
                chunks += cur
                cur = mutableListOf()
                curBytes = 0L
            }
            cur += e
            curBytes += size
        }
        if (cur.isNotEmpty()) chunks += cur
        return chunks
    }

    private fun postBulkChunk(
        serverUrl: String,
        bearerToken: String,
        appVersion: String,
        chunk: List<RepoBulkEntry>,
    ): RepoBulkOutcome {
        val envelope = JSONArray().apply {
            chunk.forEach { e ->
                put(JSONObject().apply {
                    put("relPath", e.relPath)
                    put("op", e.op)
                    put("contentHash", e.contentHash)
                    put("appVersion", appVersion)
                    put("clientSentAt", Instant.now().toString())
                })
            }
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
        chunk.forEachIndexed { i, e ->
            if (e.op == "upsert" && e.file != null) {
                bodyBuilder.addPart(
                    MultipartBody.Part.createFormData(
                        "file_$i",
                        e.file.name,
                        e.file.asRequestBody("text/markdown".toMediaType()),
                    )
                )
            }
        }

        val request = Request.Builder()
            .url("$serverUrl/v1/repo/bulk")
            .header("Authorization", "Bearer $bearerToken")
            .post(bodyBuilder.build())
            .build()

        return try {
            bulkClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return RepoBulkOutcome.Failure("HTTP ${response.code}: ${body.take(200)}")
                }
                val resultsJson = runCatching { JSONObject(body).getJSONArray("results") }
                    .getOrElse { return RepoBulkOutcome.Failure("Risposta bulk illeggibile: ${body.take(200)}") }
                val parsed = (0 until resultsJson.length()).map { idx ->
                    val o = resultsJson.getJSONObject(idx)
                    RepoBulkResult(
                        relPath = o.optString("relPath"),
                        status = o.optString("status", "error"),
                        error = if (o.has("error")) o.optString("error") else null,
                    )
                }
                if (parsed.size != chunk.size) {
                    return RepoBulkOutcome.Failure(
                        "Risposta bulk incoerente: ${parsed.size} risultati per ${chunk.size} voci"
                    )
                }
                RepoBulkOutcome.Applied(parsed)
            }
        } catch (e: Exception) {
            RepoBulkOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

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
