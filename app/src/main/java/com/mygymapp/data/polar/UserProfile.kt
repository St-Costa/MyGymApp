package com.mygymapp.data.polar

import android.content.Context
import com.mygymapp.data.parser.UserProfileParser
import com.mygymapp.data.repository.FileManager
import com.mygymapp.data.sync.RepoLedgerRepository
import com.mygymapp.data.sync.RepoSyncWorker
import com.mygymapp.data.sync.SyncConfigRepository
import com.mygymapp.data.util.AppLogger
import com.mygymapp.data.util.AtomicFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Year
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fallback age (years) used by every age-dependent formula when [UserProfile.birthYear]
 *  hasn't been set yet — matches the old plain-[age]-field default, so a fresh profile keeps
 *  producing the same numbers it always did until the user fills in a real birth year. */
private const val DEFAULT_AGE = 30

data class UserProfile(
    val weightKg: Double = 75.0,
    val isMale: Boolean = true,
    val heightCm: Int = 175,
    // Real birth year — the sole source of age for every formula (Keytel calories,
    // estimated HRmax, TRIMP, VO2max, BIA body-fat %). Null until the user fills it in (ProfileSection);
    // effectiveAge falls back to DEFAULT_AGE until then.
    val birthYear: Int? = null,
) {
    /** [birthYear]-derived age, recomputed from the current calendar year (never stale like a
     *  manually-entered plain age field would be) — [DEFAULT_AGE] until birthYear is set. */
    val effectiveAge: Int get() = birthYear?.let { Year.now().value - it } ?: DEFAULT_AGE

    /**
     * Single source of truth for estimated max HR: the unweighted mean of the Fox, Tanaka
     * and Gulati formulas ([HrZoneCalculator.estimatedMaxHr], mirroring the server's
     * `_estimated_max_hr`). Everything that needs an max HR — live zone boundaries, the
     * uneven-detection gate, Banister TRIMP, Uth VO2max — reads this, so the live values
     * agree with the server's retrospective computation instead of diverging by 2–4 BPM
     * (the old Tanaka-only value).
     */
    val hrMax: Int get() = HrZoneCalculator.estimatedMaxHr(effectiveAge)
}

/**
 * File-backed user profile. The source of truth is `gymdata/exercises/_settings.md`
 * ([SETTINGS_REL_PATH]) — a plain file under `gymdata/` so the profile survives in the
 * tar backup (`scripts/install-debug.sh`) and in the server full-store backup like any
 * other repo file (see `docs/BACKUP.md` §6, `docs/STORAGE.md`).
 *
 * Why under `exercises/` with a `_` prefix: the server's repo-file endpoint only accepts
 * paths under `exercises/`/`routines/` (`docs/backup-server-brief.md` §2.1), and it
 * already excludes `_`-prefixed basenames from the parsed SQL view by convention — so
 * the settings file is stored, committed, manifested and restored like everything else
 * without polluting the exercise tables. `ExerciseRepository` skips `_`-prefixed files
 * on load for the same reason. Secrets (server URL, bearer token) stay out — they live
 * in `EncryptedSharedPreferences` and must never be backed up to the server they
 * authenticate against.
 *
 * `get()` stays synchronous (callers read it at ~1Hz during HR sessions) via an
 * in-memory cache; `save()` is `suspend` and writes the file before queueing it in the
 * repo ledger. First read migrates the legacy `user_profile` SharedPreferences once,
 * then deletes that store.
 */
@Singleton
class UserProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileManager: FileManager,
    private val repoLedgerRepository: RepoLedgerRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "UserProfileRepository"
        const val SETTINGS_REL_PATH = "exercises/_settings.md"
        private const val LEGACY_PREFS = "user_profile"
    }

    @Volatile private var cached: UserProfile? = null
    private val lock = Any()

    // Fire-and-forget only for the one-time legacy migration out of the synchronous
    // get() path (the file write itself is blocking and tiny; the ledger queue can't
    // run there because it's suspend). Saves go through suspend save() directly.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun settingsFile(): File = File(fileManager.root, SETTINGS_REL_PATH)

    fun get(): UserProfile = cached ?: synchronized(lock) {
        cached ?: load().also { cached = it }
    }

    suspend fun save(profile: UserProfile) = withContext(Dispatchers.IO) {
        val file = settingsFile()
        file.parentFile?.mkdirs()
        AtomicFiles.writeTextAtomically(file, UserProfileParser.toMarkdown(profile))
        synchronized(lock) { cached = profile }
        queueForBackup()
    }

    /** Re-reads the file (e.g. after "Ripristina" wrote it bypassing this repo). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val fresh = readFile() ?: return@withContext
        synchronized(lock) { cached = fresh }
    }

    private fun load(): UserProfile {
        readFile()?.let { return it }
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val profile = UserProfile(
            weightKg = prefs.getFloat("weightKg", 75f).toDouble(),
            isMale = prefs.getBoolean("isMale", true),
            heightCm = prefs.getInt("heightCm", 175),
            birthYear = prefs.getInt("birthYear", -1).takeIf { it > 0 },
        )
        // contains() distinguishes "user ever saved" from defaults — save() always wrote
        // every key, so any present key means there is something worth migrating.
        val customized = prefs.contains("weightKg") || prefs.contains("isMale") ||
            prefs.contains("heightCm") || prefs.contains("birthYear")
        if (customized) {
            runCatching {
                val file = settingsFile()
                file.parentFile?.mkdirs()
                AtomicFiles.writeTextAtomically(file, UserProfileParser.toMarkdown(profile))
                context.deleteSharedPreferences(LEGACY_PREFS)
            }.onSuccess {
                appLogger.i(TAG, "Migrated user profile to $SETTINGS_REL_PATH")
                ioScope.launch { runCatching { queueForBackup() } }
            }.onFailure {
                appLogger.e(TAG, "Profile migration to $SETTINGS_REL_PATH failed", it)
            }
        }
        return profile
    }

    private fun readFile(): UserProfile? {
        val file = settingsFile()
        if (!file.exists()) return null
        return try {
            UserProfileParser.fromMarkdown(file.readText())
        } catch (e: Exception) {
            appLogger.e(TAG, "Unparsable $SETTINGS_REL_PATH, using defaults", e)
            null
        }
    }

    /**
     * Queues the settings file in the repo ledger so it rides out with the next repo
     * sync — same discipline as `ExerciseRepository.save()`: always queued, immediate
     * upload only when the sync toggle is on.
     */
    private suspend fun queueForBackup() {
        val file = settingsFile()
        if (!file.exists()) return
        repoLedgerRepository.requeueIfChanged(SETTINGS_REL_PATH, file.readBytes())
        if (syncConfigRepository.isEnabled() && syncConfigRepository.isConfigured()) {
            RepoSyncWorker.Scheduler.runExpedited(context)
        }
    }
}
