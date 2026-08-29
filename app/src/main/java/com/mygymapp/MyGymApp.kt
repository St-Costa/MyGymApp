package com.mygymapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mygymapp.data.repository.WorkoutRepository
import com.mygymapp.data.sync.EcgSyncWorker
import com.mygymapp.data.sync.ReadinessSyncWorker
import com.mygymapp.data.sync.RepoSyncWorker
import com.mygymapp.data.sync.ScaleWeighInSyncWorker
import com.mygymapp.data.sync.SyncWorker
import com.mygymapp.ui.screen.main.HomeStateLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MyGymApp : Application(), ImageLoaderFactory, Configuration.Provider {

    // WorkManager needs this to construct @HiltWorker workers (SyncWorker) with their
    // injected dependencies — see docs/SYNC.md §1.3.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Populated by Hilt before onCreate(). Kicking its refresh here means the home state is
    // being built while the Activity/Compose tree is still inflating, so MainViewModel finds
    // it ready (see HomeStateLoader).
    @Inject lateinit var homeStateLoader: HomeStateLoader

    // Only used for the parser warm-up below.
    @Inject lateinit var workoutRepository: WorkoutRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Durability net: catches anything an expedited send couldn't deliver (server
        // down, phone off tailnet, app killed before the one-off ran). Retries every 4h
        // indefinitely — this is what keeps data flowing in when the self-hosted server
        // is only up intermittently (e.g. only running on a dev machine for now), not
        // just for brief outages.
        SyncWorker.Scheduler.ensurePeriodic(this)
        ReadinessSyncWorker.Scheduler.ensurePeriodic(this)
        ScaleWeighInSyncWorker.Scheduler.ensurePeriodic(this)
        EcgSyncWorker.Scheduler.ensurePeriodic(this)
        // Fifth pipeline: full-store backup of exercises/routines (docs/BACKUP.md).
        RepoSyncWorker.Scheduler.ensurePeriodic(this)

        // Cold-start warm-up. The home screen's first data load pays a one-off tax the rest
        // of the session doesn't: spinning up Dispatchers.IO, class-loading snakeyaml + the
        // markdown/session parsers, JIT-compiling them. That tax showed up as ~1s on the very
        // first home render even though the gitgraph cache read itself is tiny. Paying it here,
        // in parallel with Activity creation and layout inflation, means MainViewModel.init
        // finds everything warm. Fire-and-forget; failures are irrelevant (the real load
        // re-runs the same calls and would surface any error itself).
        // Build the home state now, off the main thread, so it's ready by first render.
        homeStateLoader.refresh()
        // …and, on a separate coroutine, class-load + warm snakeyaml / WorkoutParser so the
        // home load's first full-session parse doesn't pay that cold tax in-line.
        appScope.launch { workoutRepository.warmUpParsers() }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // 20% of available memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // filesDir is permanent storage (never cleared by Android automatically)
                    .directory(filesDir.resolve("gymdata/image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
