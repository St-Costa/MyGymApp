package com.mygymapp.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.mygymapp.data.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the self-hosted server sync configuration: the Tailscale Serve HTTPS
 * hostname, the shared-secret bearer token, and whether sync is enabled.
 *
 * The store is encrypted at rest ([EncryptedSharedPreferences], Tink AES256 via the
 * Android Keystore) — the bearer is a long-lived credential that must survive on disk,
 * and plaintext `shared_prefs/sync_config.xml` already leaked into git history once
 * (see CHANGELOG Phase 106). See `docs/SYNC.md` for the full design. Same sensitivity
 * tier as `user_profile` (STORAGE.md).
 *
 * First-run migration: values from the legacy plaintext `sync_config` file (pre-Phase 107)
 * are copied into the encrypted store once, then the legacy file is deleted. If the
 * Keystore is unusable (rare, broken hardware keystore), it falls back to the plaintext
 * file with a logged warning rather than bricking sync configuration.
 */
@Singleton
class SyncConfigRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "SyncConfigRepository"
        private const val LEGACY_NAME = "sync_config"
        private const val STORE_NAME = "sync_config_enc"
    }

    private val prefs: SharedPreferences = openStore(context)

    private fun openStore(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encrypted = EncryptedSharedPreferences.create(
                STORE_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            migrateLegacyIfNeeded(context, encrypted)
            encrypted
        } catch (e: Exception) {
            // Broken keystore: stay functional on plaintext rather than losing sync config.
            appLogger.e(TAG, "Encrypted store unavailable, using plaintext fallback", e)
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacyIfNeeded(context: Context, encrypted: SharedPreferences) {
        if (encrypted.contains("serverUrl")) return // already migrated (or already in use)
        val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        val url = legacy.getString("serverUrl", "")
        val token = legacy.getString("bearerToken", "")
        if (url.isNullOrBlank() && token.isNullOrBlank() && !legacy.contains("enabled")) {
            // Nothing was ever configured — still delete the empty legacy file if present.
            context.deleteSharedPreferences(LEGACY_NAME)
            return
        }
        encrypted.edit()
            .putString("serverUrl", url ?: "")
            .putString("bearerToken", token ?: "")
            .putBoolean("enabled", legacy.getBoolean("enabled", false))
            .putString("tarballManifestSha", legacy.getString("tarballManifestSha", "") ?: "")
            .apply()
        context.deleteSharedPreferences(LEGACY_NAME)
        appLogger.i(TAG, "Migrated sync config to the encrypted store")
    }

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
