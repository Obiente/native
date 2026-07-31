package dev.obiente.nextcloudnative

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.obiente.nextcloudnative.app.ActivityNotificationDestination
import dev.obiente.nextcloudnative.app.DynamicActivityNotificationPlan
import dev.obiente.nextcloudnative.app.NextcloudActivitySemantic

internal sealed interface NextcloudNotificationEvent {
    val id: Int
    val accountKey: String

    data class Message(
        override val id: Int,
        override val accountKey: String,
        val conversationId: String,
        val conversationName: String,
        val sender: String,
        val preview: String,
        val timestamp: Long,
    ) : NextcloudNotificationEvent

    data class IncomingCall(
        override val id: Int,
        override val accountKey: String,
        val conversationId: String,
        val caller: String,
        val video: Boolean,
    ) : NextcloudNotificationEvent

    data class Transfer(
        override val id: Int,
        override val accountKey: String,
        val name: String,
        val completedBytes: Long,
        val totalBytes: Long?,
        val uploading: Boolean,
    ) : NextcloudNotificationEvent

    data class SyncConflict(
        override val id: Int,
        override val accountKey: String,
        val path: String,
        val detail: String,
    ) : NextcloudNotificationEvent

    data class MediaReady(
        override val id: Int,
        override val accountKey: String,
        val title: String,
        val detail: String,
        val preview: Bitmap? = null,
    ) : NextcloudNotificationEvent

    data class Reminder(
        override val id: Int,
        override val accountKey: String,
        val title: String,
        val detail: String,
        val timestamp: Long,
    ) : NextcloudNotificationEvent

    data class Activity(
        override val id: Int,
        override val accountKey: String,
        val title: String,
        val detail: String,
        val semantic: NextcloudActivitySemantic = NextcloudActivitySemantic.General,
    ) : NextcloudNotificationEvent

    data class AppUpdateAvailable(
        override val id: Int,
        override val accountKey: String,
        val versionName: String,
    ) : NextcloudNotificationEvent
}

internal data class NextcloudNotificationPolicy(
    val channelId: String,
    val groupKey: String,
    val category: String,
    val priority: Int,
    val ongoing: Boolean = false,
)

internal fun NextcloudNotificationEvent.notificationPolicy(): NextcloudNotificationPolicy = when (this) {
    is NextcloudNotificationEvent.Message -> NextcloudNotificationPolicy(
        CHANNEL_MESSAGES, "messages:$accountKey:$conversationId", NotificationCompat.CATEGORY_MESSAGE,
        NotificationCompat.PRIORITY_HIGH,
    )
    is NextcloudNotificationEvent.IncomingCall -> NextcloudNotificationPolicy(
        CHANNEL_CALLS, "calls:$accountKey", NotificationCompat.CATEGORY_CALL,
        NotificationCompat.PRIORITY_MAX, ongoing = true,
    )
    is NextcloudNotificationEvent.Transfer -> NextcloudNotificationPolicy(
        CHANNEL_TRANSFERS, "transfers:$accountKey", NotificationCompat.CATEGORY_PROGRESS,
        NotificationCompat.PRIORITY_LOW, ongoing = totalBytes == null || completedBytes < totalBytes,
    )
    is NextcloudNotificationEvent.SyncConflict -> NextcloudNotificationPolicy(
        CHANNEL_SYNC, "sync:$accountKey", NotificationCompat.CATEGORY_ERROR,
        NotificationCompat.PRIORITY_HIGH,
    )
    is NextcloudNotificationEvent.MediaReady -> NextcloudNotificationPolicy(
        CHANNEL_MEDIA, "media:$accountKey", NotificationCompat.CATEGORY_STATUS,
        NotificationCompat.PRIORITY_DEFAULT,
    )
    is NextcloudNotificationEvent.Reminder -> NextcloudNotificationPolicy(
        CHANNEL_REMINDERS, "reminders:$accountKey", NotificationCompat.CATEGORY_REMINDER,
        NotificationCompat.PRIORITY_HIGH,
    )
    is NextcloudNotificationEvent.Activity -> when (semantic) {
        NextcloudActivitySemantic.Message -> NextcloudNotificationPolicy(
            CHANNEL_MESSAGES, "activity:messages:$accountKey", NotificationCompat.CATEGORY_MESSAGE,
            NotificationCompat.PRIORITY_HIGH,
        )
        NextcloudActivitySemantic.Media -> NextcloudNotificationPolicy(
            CHANNEL_MEDIA, "activity:media:$accountKey", NotificationCompat.CATEGORY_STATUS,
            NotificationCompat.PRIORITY_DEFAULT,
        )
        NextcloudActivitySemantic.File -> NextcloudNotificationPolicy(
            CHANNEL_ACTIVITY, "activity:files:$accountKey", NotificationCompat.CATEGORY_SOCIAL,
            NotificationCompat.PRIORITY_DEFAULT,
        )
        NextcloudActivitySemantic.General -> NextcloudNotificationPolicy(
            CHANNEL_ACTIVITY, "activity:$accountKey", NotificationCompat.CATEGORY_SOCIAL,
            NotificationCompat.PRIORITY_DEFAULT,
        )
    }
    is NextcloudNotificationEvent.AppUpdateAvailable -> NextcloudNotificationPolicy(
        CHANNEL_APP_UPDATES,
        "app-updates",
        NotificationCompat.CATEGORY_STATUS,
        NotificationCompat.PRIORITY_DEFAULT,
    )
}

internal fun DynamicActivityNotificationPlan.toAndroidNotificationEvent(
    accountKey: String,
): NextcloudNotificationEvent.Activity {
    require(groupKey.endsWith(":$accountKey")) { "The activity notification plan belongs to another account." }
    val expectedDestination = when (semantic) {
        NextcloudActivitySemantic.Message -> ActivityNotificationDestination.Talk
        NextcloudActivitySemantic.Media -> ActivityNotificationDestination.Media
        NextcloudActivitySemantic.File -> ActivityNotificationDestination.Files
        NextcloudActivitySemantic.General -> ActivityNotificationDestination.Activity
    }
    require(destination == expectedDestination) { "The activity notification destination does not match its semantic." }
    return NextcloudNotificationEvent.Activity(
        id = notificationId,
        accountKey = accountKey,
        title = title,
        detail = detail,
        semantic = semantic,
    )
}

internal class AndroidNotificationCoordinator(private val context: Context) {
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        notificationChannels().forEach(manager::createNotificationChannel)
    }

    fun post(event: NextcloudNotificationEvent): Boolean {
        ensureChannels()
        val policy = event.notificationPolicy()
        if (!notificationDeliveryAllowed(context, policy.channelId)) return false
        val builder = NotificationCompat.Builder(context, policy.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF8F5EAD.toInt())
            .setContentIntent(openAppIntent("open", event.id))
            .setAutoCancel(!policy.ongoing)
            .setOngoing(policy.ongoing)
            .setOnlyAlertOnce(event is NextcloudNotificationEvent.Transfer)
            .setCategory(policy.category)
            .setPriority(policy.priority)
            .setGroup(policy.groupKey)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedNotification(policy.channelId))

        when (event) {
            is NextcloudNotificationEvent.Message -> {
                val sender = Person.Builder().setName(event.sender).build()
                builder.setContentTitle(event.conversationName)
                    .setContentText(event.preview)
                    .setStyle(
                        NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                            .setConversationTitle(event.conversationName)
                            .addMessage(event.preview, event.timestamp, sender),
                    )
                    .addAction(0, "Reply", openAppIntent("reply:${event.conversationId}", event.id + 10_000))
                    .addAction(0, "Mark read", openAppIntent("read:${event.conversationId}", event.id + 20_000))
            }
            is NextcloudNotificationEvent.IncomingCall -> {
                val caller = Person.Builder().setName(event.caller).setImportant(true).build()
                builder.setContentTitle(event.caller)
                    .setContentText(if (event.video) "Incoming video call" else "Incoming audio call")
                    .setStyle(
                        NotificationCompat.CallStyle.forIncomingCall(
                            caller,
                            openAppIntent("decline:${event.conversationId}", event.id + 10_000),
                            openAppIntent("answer:${event.conversationId}", event.id + 20_000),
                        ),
                    )
            }
            is NextcloudNotificationEvent.Transfer -> {
                val progress = event.totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((event.completedBytes.coerceIn(0, total) * 100L) / total).toInt()
                }
                builder.setContentTitle(if (event.uploading) "Uploading ${event.name}" else "Downloading ${event.name}")
                    .setContentText(progress?.let { "$it% complete" } ?: "Working...")
                    .setProgress(100, progress ?: 0, progress == null)
            }
            is NextcloudNotificationEvent.SyncConflict -> builder
                .setContentTitle("Sync conflict")
                .setContentText(event.path)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
                .addAction(0, "Review", openAppIntent("conflict:${event.path}", event.id + 10_000))
            is NextcloudNotificationEvent.MediaReady -> {
                builder.setContentTitle(event.title).setContentText(event.detail)
                event.preview?.let { bitmap ->
                    builder.setLargeIcon(bitmap)
                        .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
                }
            }
            is NextcloudNotificationEvent.Reminder -> builder
                .setContentTitle(event.title)
                .setContentText(event.detail)
                .setWhen(event.timestamp)
                .setShowWhen(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            is NextcloudNotificationEvent.Activity -> builder
                .setContentTitle(event.title)
                .setContentText(event.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            is NextcloudNotificationEvent.AppUpdateAvailable -> builder
                .setContentTitle("Nextcloud Native update available")
                .setContentText("Version ${event.versionName} is ready to review")
                .setContentIntent(openAppIntent(ACTION_REVIEW_APP_UPDATE, event.id))
        }
        NotificationManagerCompat.from(context).notify(event.accountKey, event.id, builder.build())
        return true
    }

    private fun openAppIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .setAction("dev.obiente.nextcloudnative.notification.$action")
            .addFlags(NOTIFICATION_ACTIVITY_FLAGS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun redactedNotification(channelId: String) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Nextcloud Native")
        .setContentText("Open the app to view this update")
        .build()
}

internal val NOTIFICATION_ACTIVITY_FLAGS: Int =
    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

internal fun notificationDeliveryAllowed(context: Context, channelId: String): Boolean {
    val runtimePermissionGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelImportance = if (Build.VERSION.SDK_INT >= 26) {
        context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(channelId)
            ?.importance
    } else {
        null
    }
    return notificationDeliveryAllowed(
        sdk = Build.VERSION.SDK_INT,
        runtimePermissionGranted = runtimePermissionGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        channelImportance = channelImportance,
    )
}

internal fun notificationDeliveryAllowed(
    sdk: Int,
    runtimePermissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    channelImportance: Int?,
): Boolean =
    runtimePermissionGranted &&
        appNotificationsEnabled &&
        (sdk < 26 || channelImportance == null || channelImportance != NotificationManager.IMPORTANCE_NONE)

private fun notificationChannels(): List<NotificationChannel> = if (Build.VERSION.SDK_INT < 26) emptyList() else listOf(
    NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Incoming Talk calls"
        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        setShowBadge(true)
    },
    NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Talk messages and mentions"
        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    },
    NotificationChannel(CHANNEL_TRANSFERS, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Uploads, downloads, and offline sync progress"
    },
    NotificationChannel(CHANNEL_SYNC, "Sync and conflicts", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Sync completion, errors, and conflicts"
    },
    NotificationChannel(CHANNEL_MEDIA, "Media processing", NotificationManager.IMPORTANCE_LOW).apply {
        description = "RAW previews, indexing, face recognition, and photo edits"
    },
    NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Calendar, task, Deck, and app reminders"
    },
    NotificationChannel(CHANNEL_ACTIVITY, "Nextcloud activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Shares, comments, app events, and administrative updates"
    },
    NotificationChannel(CHANNEL_APP_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "New Nextcloud Native versions ready to review"
    },
)

internal const val CHANNEL_MESSAGES = "nextcloud_messages"
internal const val CHANNEL_CALLS = "nextcloud_calls"
internal const val CHANNEL_TRANSFERS = "nextcloud_transfers"
internal const val CHANNEL_SYNC = "nextcloud_sync"
internal const val CHANNEL_MEDIA = "nextcloud_media"
internal const val CHANNEL_REMINDERS = "nextcloud_reminders"
internal const val CHANNEL_ACTIVITY = "nextcloud_activity"
internal const val CHANNEL_APP_UPDATES = "nextcloud_app_updates"
internal const val ACTION_REVIEW_APP_UPDATE = "review-app-update"
