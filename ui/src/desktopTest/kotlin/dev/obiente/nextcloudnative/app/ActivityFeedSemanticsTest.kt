package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityFeedSemanticsTest {
    @Test
    fun `feed filters semantic app type and text then groups by day`() {
        val activities = listOf(
            activity(
                id = 3,
                app = "spreed",
                type = "chat_mention",
                subject = "Alex mentioned you",
                dateTime = "2026-07-23T18:10:00+00:00",
            ),
            activity(
                id = 2,
                app = "files",
                type = "file_created",
                subject = "Budget created",
                dateTime = "2026-07-23T12:00:00+00:00",
            ),
            activity(
                id = 1,
                app = "photos",
                type = "album_shared",
                subject = "Trip album shared",
                dateTime = "2026-07-22T09:00:00+00:00",
            ),
        )

        val all = buildActivityFeedPresentation(activities)
        assertEquals(listOf("Jul 23, 2026", "Jul 22, 2026"), all.groups.map(ActivityFeedDayGroup::label))
        assertEquals(2, all.groups.first().activities.size)
        assertEquals(1, all.semanticCounts[NextcloudActivitySemantic.Message])
        assertEquals(3, all.appFacets.size)

        val filtered = buildActivityFeedPresentation(
            activities,
            ActivityFeedFilter(
                query = "alex",
                app = "spreed",
                type = "chat_mention",
                semantic = NextcloudActivitySemantic.Message,
            ),
        )
        assertEquals(listOf(3L), filtered.groups.flatMap(ActivityFeedDayGroup::activities).map(NextcloudActivity::id))
    }

    @Test
    fun `technical object ids are neither searchable nor exposed in actions`() {
        val secretId = "987654-private-object-token"
        val item = activity(
            id = 9,
            app = "files",
            type = "file_changed",
            subject = "Document updated",
            objectId = secretId,
        )

        assertEquals(
            0,
            buildActivityFeedPresentation(listOf(item), ActivityFeedFilter(query = secretId)).matchedCount,
        )
        val action = item.activityOpenAction(setOf("files"), "https://cloud.example.test")
        assertEquals("Go to Files", action?.label)
        assertFalse(action?.label.orEmpty().contains(secretId))
    }

    @Test
    fun `file activity opens its verified native parent without treating a file as a folder`() {
        val item = activity(
            id = 10,
            app = "files",
            type = "file_changed",
            subject = "Roadmap changed",
            objectName = "/Projects/Phoenix/Roadmap.md",
        )

        val action = item.activityOpenAction(setOf("files"), "https://cloud.example.test")

        assertEquals("Show in Files", action?.label)
        assertEquals("Projects/Phoenix", action?.filesParentPath)
        assertNull(action?.appId)
        assertNull(action?.sameOriginUrl)
        assertNull(
            item.copy(objectName = "/Projects/../Secrets/key.txt")
                .activityOpenAction(setOf("files"), "https://cloud.example.test")
                ?.filesParentPath,
        )
    }

    @Test
    fun `media presentation retains the parent folder for files activity`() {
        val item = activity(
            id = 11,
            app = "files",
            type = "file_changed",
            subject = "Photo updated",
            objectName = "/Photos/Summer/beach.jpg",
        )

        val action = item.activityOpenAction(setOf("files", "photos"), "https://cloud.example.test")

        assertEquals("Show in Files", action?.label)
        assertEquals("Photos/Summer", action?.filesParentPath)
    }

    @Test
    fun `activity actions prefer native apps and only permit same origin browser links`() {
        val talk = activity(
            id = 4,
            app = "spreed",
            type = "chat",
            subject = "New message",
            link = "https://cloud.example.test/call/room",
        )
        assertEquals(
            "spreed",
            talk.activityOpenAction(setOf("spreed", "files"), "https://cloud.example.test")?.appId,
        )

        val unknown = activity(
            id = 5,
            app = "custom_app",
            type = "custom",
            subject = "Custom event",
            link = "/index.php/apps/custom_app/item/5",
        )
        val browserAction = unknown.activityOpenAction(emptySet(), "https://cloud.example.test/")
        assertEquals("https://cloud.example.test/index.php/apps/custom_app/item/5", browserAction?.sameOriginUrl)
        assertEquals("Open", browserAction?.label)

        assertNull(
            unknown.copy(link = "https://cloud.example.test.evil.invalid/item")
                .activityOpenAction(emptySet(), "https://cloud.example.test"),
        )
        assertNull(
            unknown.copy(link = "https://user@cloud.example.test/item")
                .activityOpenAction(emptySet(), "https://cloud.example.test"),
        )
        assertTrue(buildNextcloudActivityPageRequest().body == null)
        assertEquals(NextcloudApiMethod.GET, buildNextcloudActivityPageRequest().method)
    }

    private fun activity(
        id: Long,
        app: String,
        type: String,
        subject: String,
        message: String? = null,
        objectId: String? = null,
        objectName: String? = null,
        link: String? = null,
        dateTime: String? = "2026-07-23T12:00:00Z",
    ) = NextcloudActivity(
        id = id,
        app = app,
        type = type,
        subject = subject,
        message = message,
        objectType = null,
        objectId = objectId,
        objectName = objectName,
        link = link,
        icon = null,
        dateTime = dateTime,
    )
}
