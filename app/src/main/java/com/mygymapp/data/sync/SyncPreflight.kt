package com.mygymapp.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mygymapp.data.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Request
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SyncPreflight(
    val requestId: String,
    val checkedAt: String,
    val available: Boolean,
    val durationMs: Long,
    val reason: String,
) {
    fun headers(): Map<String, String> = mapOf(
        "X-Client-Request-Id" to requestId,
        "X-Client-Preflight" to if (available) "ok" else "failed",
        "X-Client-Preflight-Duration-Ms" to durationMs.toString(),
        "X-Client-Preflight-At" to checkedAt,
        "X-Client-Preflight-Reason" to reason.take(120),
    )
}

/**
 * True when [serverUrl] would send the bearer token over cleartext HTTP to a host
 * outside the private networks (LAN, Tailscale CGNAT, tailnet DNS, loopback).
 * Pure function (JDK `URI` only, no Android/OkHttp) so it is unit-testable.
 * Malformed URLs return false — that case already has its own "invalid server URL" path.
 */
fun isCleartextToPublicHost(serverUrl: String): Boolean {
    val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() != "http") return false
    val host = uri.host?.lowercase() ?: return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".ts.net") || host.endsWith(".internal") || host == "::1"
    ) return false
    if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return false
    if (host.startsWith("172.")) {
        val second = host.split(".").getOrNull(1)?.toIntOrNull()
        if (second != null && second in 16..31) return false
    }
    if (host.startsWith("100.")) {
        // Tailscale CGNAT range 100.64.0.0/10.
        val second = host.split(".").getOrNull(1)?.toIntOrNull()
        if (second != null && second in 64..127) return false
    }
    // Anything else — public IP or public DNS — must not receive the token in cleartext.
    return true
}

/** Verifies the actual Tailscale-served endpoint before a payload is uploaded. */
@Singleton
class SyncHealthProbe @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "SyncHealthProbe"
        private const val SLOW_HEALTH_MS = 1_500L
    }

    fun check(serverUrl: String, bearerToken: String): SyncPreflight {
        val requestId = UUID.randomUUID().toString()
        val checkedAt = Instant.now().toString()
        val startedAt = System.currentTimeMillis()
        if (isCleartextToPublicHost(serverUrl)) {
            val result = SyncPreflight(
                requestId, checkedAt, false, 0,
                "cleartext HTTP to a non-private host — use the Tailscale Serve HTTPS URL",
            )
            appLogger.w(TAG, "preflight id=$requestId unavailable reason=${result.reason}")
            return result
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        if (network == null || capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            val result = SyncPreflight(requestId, checkedAt, false, 0, "network unavailable")
            appLogger.w(TAG, "preflight id=$requestId unavailable reason=${result.reason}")
            return result
        }

        val request = runCatching {
            Request.Builder()
                .url("${serverUrl.trimEnd('/')}/health")
                .header("Authorization", "Bearer $bearerToken")
                .header("X-Client-Request-Id", requestId)
                .get()
                .build()
        }.getOrElse { error ->
            val result = SyncPreflight(requestId, checkedAt, false, 0, "invalid server URL")
            appLogger.w(TAG, "preflight id=$requestId unavailable reason=${error.message}")
            return result
        }

        return try {
            SyncHttpClients.health.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startedAt
                val available = response.isSuccessful
                val reason = if (available) {
                    if (durationMs >= SLOW_HEALTH_MS) "slow health response" else "ok"
                } else {
                    "HTTP ${response.code}"
                }
                val result = SyncPreflight(requestId, checkedAt, available, durationMs, reason)
                if (available) {
                    appLogger.i(TAG, "preflight id=$requestId ok durationMs=$durationMs slow=${durationMs >= SLOW_HEALTH_MS}")
                } else {
                    appLogger.w(TAG, "preflight id=$requestId unavailable reason=$reason durationMs=$durationMs")
                }
                result
            }
        } catch (error: Exception) {
            val durationMs = System.currentTimeMillis() - startedAt
            val reason = "${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            appLogger.w(TAG, "preflight id=$requestId unavailable reason=$reason durationMs=$durationMs")
            SyncPreflight(requestId, checkedAt, false, durationMs, reason)
        }
    }
}

/** Per-thread metadata attached to the next synchronous OkHttp call. */
object SyncRequestContext {
    private val headers = ThreadLocal<Map<String, String>?>()

    fun <T> with(preflight: SyncPreflight, block: () -> T): T {
        val previous = headers.get()
        headers.set(preflight.headers())
        return try {
            block()
        } finally {
            headers.set(previous)
        }
    }

    fun currentHeaders(): Map<String, String> = headers.get().orEmpty()
}
