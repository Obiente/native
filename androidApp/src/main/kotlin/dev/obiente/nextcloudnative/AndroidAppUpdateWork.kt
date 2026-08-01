package dev.obiente.nextcloudnative

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.obiente.nextcloudnative.app.AndroidUpdateChannel
import dev.obiente.nextcloudnative.app.AppUpdateCheckResult
import dev.obiente.nextcloudnative.app.AppUpdatePreferences
import java.util.concurrent.TimeUnit

internal object AndroidAppUpdateWork {
    private const val UNIQUE_WORK = "nextcloud-native-app-update-check"

    fun schedule(context: Context, preferences: AppUpdatePreferences) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!BuildConfig.DIRECT_APK_UPDATES || !preferences.automaticChecks) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (preferences.unmeteredNetworkOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = PeriodicWorkRequestBuilder<AndroidAppUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

internal class AndroidAppUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val services = AndroidNextcloudServices(applicationContext)
        val preferences = services.loadAppUpdatePreferences()
        if (!preferences.automaticChecks) return Result.success()
        return when (val check = services.checkForAppUpdate(automatic = true)) {
            is AppUpdateCheckResult.Failed -> if (check.retryable) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

internal fun automaticAndroidUpdateCheckAllowed(
    preferences: AppUpdatePreferences,
    networkMetered: Boolean,
): Boolean =
    preferences.automaticChecks &&
        (!preferences.unmeteredNetworkOnly || !networkMetered)

internal fun isAndroidActiveNetworkMetered(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

internal class AndroidAppUpdateNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun notifyIfNeeded(
        channel: AndroidUpdateChannel,
        update: AppUpdateCheckResult.Available,
        enabled: Boolean,
    ): Boolean {
        if (!shouldNotifyAppUpdate(
                lastNotifiedVersionCode = preferences.getLong(
                    "$KEY_LAST_NOTIFIED_PREFIX${channel.storageValue}",
                    0L,
                ),
                discoveredVersionCode = update.release.versionCode,
                enabled = enabled,
            )
        ) return false
        val key = "$KEY_LAST_NOTIFIED_PREFIX${channel.storageValue}"
        val posted = AndroidNotificationCoordinator(appContext).post(
            NextcloudNotificationEvent.AppUpdateAvailable(
                id = APP_UPDATE_NOTIFICATION_ID,
                accountKey = "device",
                versionName = update.release.versionName,
                versionCode = update.release.versionCode,
            ),
        )
        if (posted) preferences.edit().putLong(key, update.release.versionCode).apply()
        return posted
    }

    private companion object {
        const val PREFERENCES = "app-update-notifications-v1"
        const val KEY_LAST_NOTIFIED_PREFIX = "last-notified-version-"
        const val APP_UPDATE_NOTIFICATION_ID = 0x4E435550
    }
}

internal fun shouldNotifyAppUpdate(
    lastNotifiedVersionCode: Long,
    discoveredVersionCode: Long,
    enabled: Boolean,
): Boolean = enabled && discoveredVersionCode > lastNotifiedVersionCode
