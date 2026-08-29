package com.mygymapp.data.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One entry from `GET /v1/manifest`: a server-side file and the sha256 of its bytes. */
data class ManifestEntry(val relPath: String, val contentHash: String)

/** One file in a `POST /v1/repo/files` response — [bytes] is null when the server said `absent`. */
data class BatchFile(val relPath: String, val bytes: ByteArray?, val contentHash: String?) {
    val present: Boolean get() = bytes != null
}

/** One member of a `GET /v1/repo/tarball` archive. */
data class TarballMember(val relPath: String, val bytes: ByteArray)

sealed class RestoreResult {
    data class Manifest(val entries: List<ManifestEntry>) : RestoreResult()
    data class FileBytes(val relPath: String, val bytes: ByteArray) : RestoreResult() {
        override fun equals(other: Any?) =
            other is FileBytes && other.relPath == relPath && other.bytes.contentEquals(bytes)
        override fun hashCode() = 31 * relPath.hashCode() + bytes.contentHashCode()
    }
    /** `POST /v1/repo/files` — one [BatchFile] per requested path, in request order. */
    data class Files(val files: List<BatchFile>) : RestoreResult()
    /**
     * `GET /v1/repo/tarball` — [members] is empty when the server's `X-Manifest-SHA256`
     * still matched the `since` we sent (nothing changed). [manifestSha] is that header,
     * to persist and send back as `?since=` next time.
     */
    data class Tarball(val manifestSha: String, val members: List<TarballMember>) : RestoreResult()
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

    /**
     * `POST /v1/repo/files` — pull many live files in one round-trip (the "Verifica
     * backup" read-back, a targeted restore). Body `{"relPaths":[…]}`; response is
     * `multipart/mixed`, one part per requested path **in request order**, each with
     * `X-Status: present|absent`, `X-Content-SHA256` (present only), and the raw bytes as
     * the part body. Caps at 500 paths server-side — the caller chunks. Never throws.
     */
    fun fetchFiles(serverUrl: String, bearerToken: String, relPaths: List<String>): RestoreResult {
        if (relPaths.isEmpty()) return RestoreResult.Files(emptyList())
        val body = JSONObject().put("relPaths", JSONArray(relPaths)).toString()
        val request = Request.Builder()
            .url("$serverUrl/v1/repo/files")
            .header("Authorization", "Bearer $bearerToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RestoreResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
                val respBody = response.body ?: return RestoreResult.Failure("Risposta vuota da /v1/repo/files")
                val out = ArrayList<BatchFile>(relPaths.size)
                MultipartReader(respBody).use { reader ->
                    var part = reader.nextPart()
                    var idx = 0
                    while (part != null) {
                        // Prefer the part's own name (Content-Disposition name="<relPath>"),
                        // fall back to positional alignment with the request.
                        val name = part.headers["Content-Disposition"]
                            ?.let { Regex("""name="([^"]*)"""").find(it)?.groupValues?.get(1) }
                            ?.takeIf { it.isNotBlank() }
                            ?: relPaths.getOrNull(idx) ?: "?"
                        val present = part.headers["X-Status"].equals("present", ignoreCase = true)
                        val hash = part.headers["X-Content-SHA256"]
                        val bytes = part.body.readByteArray()
                        out += BatchFile(name, if (present) bytes else null, hash)
                        part = reader.nextPart()
                        idx++
                    }
                }
                RestoreResult.Files(out)
            }
        } catch (e: Exception) {
            RestoreResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * `GET /v1/repo/tarball?since=<hash>` — a from-scratch restore in one transfer. The
     * response is a gzip-compressed tar of every live file (member names = relPaths) with
     * an `X-Manifest-SHA256` header. Passing back the previous run's hash as [since] yields
     * an empty tar when nothing changed. Never throws.
     *
     * The tar is read with a small ustar reader ([UstarReader]) — no extra dependency; the
     * archive is server-generated so the format is predictable (512-byte blocks, optional
     * PAX/GNU long-name headers handled).
     */
    fun fetchTarball(serverUrl: String, bearerToken: String, since: String?): RestoreResult {
        val httpUrl = (serverUrl.toHttpUrlOrNull() ?: return RestoreResult.Failure("URL non valido"))
            .newBuilder()
            .addPathSegments("v1/repo/tarball")
            .apply { if (!since.isNullOrBlank()) addQueryParameter("since", since) }
            .build()
        val request = Request.Builder()
            .url(httpUrl)
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RestoreResult.Failure("HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                }
                val manifestSha = response.header("X-Manifest-SHA256").orEmpty()
                val stream = response.body?.byteStream()
                    ?: return RestoreResult.Failure("Risposta tarball vuota")
                val members = GZIPInputStream(stream).use { gz -> UstarReader.readAll(gz) }
                RestoreResult.Tarball(manifestSha, members)
            }
        } catch (e: Exception) {
            RestoreResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
