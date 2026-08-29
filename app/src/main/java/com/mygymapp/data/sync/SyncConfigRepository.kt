package com.mygymapp.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the self-hosted server sync configuration: the Tailscale Serve HTTPS
 * hostname, the shared-secret bearer token, and whether sync is enabled.
 *
 * See `docs/SYNC.md` for the full design. `MODE_PRIVATE`, same sensitivity tier as
 * `user_profile` (STORAGE.md).
 */
@Singleton
class SyncConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("sync_config", Context.MODE_PRIVATE)

    /** Tailscale Serve hostname, e.g. `https://gym-server.tailnet-name.ts.net`. No trailing slash. */
    fun serverUrl(): String = prefs.getString("serverUrl", "") ?: ""

    /** Bearer token shared with the server, generated server-side (`openssl rand -hex 32`). */
    fun bearerToken(): String = prefs.getString("bearerToken", "") ?: ""

    /** Sync stays dormant until explicitly turned on, even if URL + token are filled in. */
    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)

    fun save(serverUrl: String, bearerToken: String, enabled: Boolean) {
        prefs.edit()
            .putString("serverUrl", serverUrl.trim().trimEnd('/'))
            .putString("bearerToken", bearerToken.trim())
            .putBoolean("enabled", enabled)
            .apply()
    }

    /**
     * Last `X-Manifest-SHA256` seen from `GET /v1/repo/tarball` — sent back as `?since=`
     * so an unchanged server answers with an empty tar. Cleared implicitly on server
     * change (a different URL makes the stored hash meaningless, but re-fetching a full
     * tarball once is harmless).
     */
    fun lastTarballManifestSha(): String = prefs.getString("tarballManifestSha", "") ?: ""

    fun setLastTarballManifestSha(sha: String) {
        prefs.edit().putString("tarballManifestSha", sha).apply()
    }

    /** True once both fields are non-blank — the minimum to attempt a send, regardless of [isEnabled]. */
    fun isConfigured(): Boolean = serverUrl().isNotBlank() && bearerToken().isNotBlank()
}
