package com.mygymapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mygymapp.data.sync.EcgSyncWorker
import com.mygymapp.data.sync.ReadinessSyncWorker
import com.mygymapp.data.sync.ScaleWeighInSyncWorker
import com.mygymapp.data.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyGymApp : Application(), ImageLoaderFactory, Configuration.Provider {

    // WorkManager needs this to construct @HiltWorker workers (SyncWorker) with their
    // injected dependencies — see docs/SYNC.md §1.3.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
