package com.theundefined.eanalizer.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theundefined.eanalizer.MainActivity
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.data.repository.EneaRepository
import io.sentry.Sentry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Daily background sync (platform JobScheduler, no WorkManager dependency). Notifies about new
 * data, and once about an expired session (the login needs the WebView, reCAPTCHA and 2FA).
 */
object BackgroundSync {
    private const val JOB_ID = 1001
    private const val CHANNEL = "sync"
    private const val NOTIFICATION_DATA = 1
    private const val NOTIFICATION_SESSION = 2

    fun schedule(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job =
            JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(4))
                .setPersisted(true)
                .build()
        scheduler.schedule(job)
    }

    /** Runs one sync unless data was already downloaded today. */
    internal suspend fun run(context: Context) {
        val repo = EneaRepository(context)
        val settings = repo.settings
        if (!settings.backgroundSync || !settings.loggedIn || settings.localOnly) return
        if (isToday(settings.lastSync)) return
        try {
            val res = repo.sync()
            if (res.downloaded.isNotEmpty())
                notify(
                    context,
                    NOTIFICATION_DATA,
                    context.getString(R.string.notif_new_data_title),
                    context.getString(R.string.notif_new_data_text),
                )
        } catch (e: SessionExpiredException) {
            if (!settings.sessionExpiryNotified) {
                settings.sessionExpiryNotified = true
                notify(
                    context,
                    NOTIFICATION_SESSION,
                    context.getString(R.string.notif_session_title),
                    context.getString(R.string.notif_session_text),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // Offline etc. - the next run retries.
        } catch (e: Exception) {
            Sentry.captureException(e)
        }
    }

    private fun isToday(millis: Long) =
        millis > 0 &&
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate() ==
                LocalDate.now()

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class SyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job =
            scope.launch {
                try {
                    BackgroundSync.run(applicationContext)
                } finally {
                    jobFinished(params, false)
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
