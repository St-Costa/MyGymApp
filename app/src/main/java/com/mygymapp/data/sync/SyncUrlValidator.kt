package com.mygymapp.data.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Single validation point for the self-hosted server URL (Sprint 1).
 *
 * Previously every API class built `Request.Builder().url("$serverUrl/...")` OUTSIDE its
 * `try`, so a typo'd URL threw `IllegalArgumentException` past the `SyncResult.Failure`
 * path and crashed the worker / Options `testConnection`. All network entry points must
 * call [errorFor] first (or use [requestUrlOrNull]) so a bad URL is a clean `Failure`
 * string, never a throw.
 *
 * Pure except for OkHttp's URL parser — unit-tested in `SyncUrlValidatorTest`.
 */
object SyncUrlValidator {

    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    /**
     * Null when [raw] is usable, otherwise a human-readable (Italian, UI-facing) reason.
     * Cleartext HTTP to a public host is rejected here too — same rule as the preflight
     * ([isCleartextToPublicHost]) — so the bearer can never be sent in the clear.
     */
    fun errorFor(raw: String): String? {
        val url = normalize(raw)
        if (url.isBlank()) return "URL vuoto"
        val httpUrl = url.toHttpUrlOrNull() ?: return "URL non valido"
        if (httpUrl.scheme != "https" && httpUrl.scheme != "http") return "URL non valido"
        if (isCleartextToPublicHost(url)) {
            return "HTTP in chiaro verso host pubblico — usa l'URL HTTPS Tailscale"
        }
        return null
    }

    /**
     * Builds `$serverUrl/path` without ever throwing: null when the base URL or the
     * resulting URL is invalid. Use it inside API methods INSTEAD of string-concat +
     * `Request.Builder().url(...)` outside the try.
     */
    fun requestUrlOrNull(serverUrl: String, vararg pathSegments: String): okhttp3.HttpUrl? {
        val base = normalize(serverUrl).toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        for (segment in pathSegments) {
            // addPathSegments would treat an embedded '/' as separators; segments here are
            // fixed endpoint names ("v1", "sessions") so addPathSegment is exact.
            builder.addPathSegment(segment)
        }
        return runCatching { builder.build() }.getOrNull()
    }
}
