package dev.bex.icloudsync.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.bex.icloudsync.settings.AppSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {
    suspend fun ensureScheduled() {
        val current = settings.state.first()
        if (!current.onboardingComplete) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(CONTENT_NAME)
            return
        }
        val network = if (current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresCharging(current.chargingOnly)
            .build()
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        scheduleContentTrigger()
    }

    fun scheduleContentTrigger() {
        val triggers = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<MediaChangedWorker>()
            .setConstraints(triggers)
            .addTag(TAG_CONTENT)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTENT_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun enqueueImmediate() {
        val current = settings.state.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(current.chargingOnly)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun enqueueRetry(afterSeconds: Long) {
        val current = settings.state.first()
        if (!current.onboardingComplete || current.paused) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(afterSeconds.coerceIn(30, TimeUnit.HOURS.toSeconds(6)), TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(current.chargingOnly)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSync() = WorkManager.getInstance(context).cancelAllWorkByTag(TAG_SYNC)

    companion object {
        private const val PERIODIC_NAME = "icloud-periodic-sync"
        private const val CONTENT_NAME = "icloud-media-observer"
        private const val IMMEDIATE_NAME = "icloud-immediate-sync"
        private const val RETRY_NAME = "icloud-server-retry"
        const val TAG_SYNC = "icloud-sync"
        private const val TAG_CONTENT = "icloud-content"
    }
}
