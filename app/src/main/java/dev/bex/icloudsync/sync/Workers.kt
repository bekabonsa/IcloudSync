package dev.bex.icloudsync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.bex.icloudsync.MainActivity
import dev.bex.icloudsync.settings.AppSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: SyncOrchestrator,
    private val settings: AppSettings,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(foreground("Preparing backup"))
        return when (val outcome = orchestrator.run { message -> setForeground(foreground(message)) }) {
            SyncOutcome.Complete -> {
                if (settings.state.first().completionNotifications) {
                    notifyAction("Backup complete", "Your current library is protected")
                }
                Result.success()
            }
            SyncOutcome.Paused -> Result.success()
            SyncOutcome.AuthRequired -> {
                notifyAction("Apple ID verification required", "Open iCloud Sync to continue backup")
                Result.success()
            }
            SyncOutcome.StorageFull -> {
                notifyAction("iCloud storage is full", "Free space or upgrade your iCloud plan, then tap Sync Now")
                Result.success()
            }
            is SyncOutcome.Retry -> {
                if (outcome.afterSeconds != null) {
                    scheduler.enqueueRetry(outcome.afterSeconds)
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            is SyncOutcome.SafeStopped -> {
                notifyAction("iCloud protocol changed", outcome.reason)
                Result.failure()
            }
        }
    }

    private fun foreground(message: String): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("iCloud Sync")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyAction(title: String, message: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(ACTION_NOTIFICATION_ID, notification)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Media backup", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL = "icloud_sync"
        const val NOTIFICATION_ID = 4101
        const val ACTION_NOTIFICATION_ID = 4102
    }
}

@HiltWorker
class MediaChangedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        scheduler.scheduleContentTrigger()
        scheduler.enqueueImmediate()
        return Result.success()
    }
}
