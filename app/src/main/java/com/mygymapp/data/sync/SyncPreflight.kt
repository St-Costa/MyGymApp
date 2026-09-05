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
