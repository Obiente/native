package dev.obiente.nextcloudnative

import androidx.core.app.NotificationCompat
import dev.obiente.nextcloudnative.app.ActivityNotificationDestination
import dev.obiente.nextcloudnative.app.DynamicActivityNotificationPlan
import dev.obiente.nextcloudnative.app.NextcloudActivitySemantic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNotificationsTest {
    @Test
    fun eventFamiliesUseIndependentChannelsAndPrivacySafeGroups() {
        val message = NextcloudNotificationEvent.Message(
            id = 1,
            accountKey = "account-a",
            conversationId = "room-7",
            conversationName = "Project",
            sender = "Someone",
            preview = "Update",
            timestamp = 1L,
        ).notificationPolicy()
        val conflict = NextcloudNotificationEvent.SyncConflict(
            id = 2,
            accountKey = "account-a",
            path = "Notes/file.md",
            detail = "Both copies changed",
        ).notificationPolicy()

        assertEquals(CHANNEL_MESSAGES, message.channelId)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, message.category)
        assertTrue(message.groupKey.contains("room-7"))
        assertEquals(CHANNEL_SYNC, conflict.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, conflict.priority)
        assertFalse(conflict.ongoing)
    }

    @Test
    fun callsAndActiveTransfersRemainOngoing() {
        val call = NextcloudNotificationEvent.IncomingCall(3, "account", "room", "Caller", true)
            .notificationPolicy()
        val transfer = NextcloudNotificationEvent.Transfer(4, "account", "photo.raw", 50, 100, true)
            .notificationPolicy()
        val completed = NextcloudNotificationEvent.Transfer(5, "account", "photo.raw", 100, 100, true)
            .notificationPolicy()

        assertEquals(CHANNEL_CALLS, call.channelId)
        assertTrue(call.ongoing)
        assertTrue(transfer.ongoing)
        assertFalse(completed.ongoing)
    }

    @Test
    fun activityPlansRouteDynamicallyWithoutMessageReadActionsOrPosting() {
        val message = activityPlan(NextcloudActivitySemantic.Message, ActivityNotificationDestination.Talk)
            .toAndroidNotificationEvent("account")
        val media = activityPlan(NextcloudActivitySemantic.Media, ActivityNotificationDestination.Media)
            .toAndroidNotificationEvent("account")
        val file = activityPlan(NextcloudActivitySemantic.File, ActivityNotificationDestination.Files)
            .toAndroidNotificationEvent("account")
        val general = activityPlan(NextcloudActivitySemantic.General, ActivityNotificationDestination.Activity)
            .toAndroidNotificationEvent("account")

        assertTrue(message is NextcloudNotificationEvent.Activity)
        assertEquals(CHANNEL_MESSAGES, message.notificationPolicy().channelId)
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, message.notificationPolicy().category)
        assertEquals(CHANNEL_MEDIA, media.notificationPolicy().channelId)
        assertEquals(CHANNEL_ACTIVITY, file.notificationPolicy().channelId)
        assertTrue(file.notificationPolicy().groupKey.contains("files"))
        assertEquals(CHANNEL_ACTIVITY, general.notificationPolicy().channelId)
        assertFailsWith<IllegalArgumentException> {
            activityPlan(NextcloudActivitySemantic.Message, ActivityNotificationDestination.Talk)
                .toAndroidNotificationEvent("another-account")
        }
        assertFailsWith<IllegalArgumentException> {
            activityPlan(NextcloudActivitySemantic.Message, ActivityNotificationDestination.Files)
                .toAndroidNotificationEvent("account")
        }
    }

    private fun activityPlan(
        semantic: NextcloudActivitySemantic,
        destination: ActivityNotificationDestination,
    ) = DynamicActivityNotificationPlan(
        notificationId = 73,
        semantic = semantic,
        destination = destination,
        groupKey = "activity:${destination.name.lowercase()}:account",
        title = "Update",
        detail = "Open Nextcloud to view",
    )
}
