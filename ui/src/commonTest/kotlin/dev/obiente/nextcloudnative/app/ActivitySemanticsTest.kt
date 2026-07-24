package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySemanticsTest {
    @Test
    fun `nullable and malformed activity fields never enter render state as null strings`() {
        val page = parseNextcloudActivityPage(
            response(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
                  null,
                  {"activity_id":null,"subject":"ignored"},
                  {"activity_id":"bad","subject":"ignored"},
                  {
                    "activity_id":42,
                    "app":null,
                    "type":null,
                    "subject":null,
                    "message":null,
                    "object_type":null,
                    "object_id":73,
                    "object_name":null,
                    "link":null,
                    "icon":null,
                    "datetime":null
                  },
                  {
                    "activity_id":41,
                    "app":"files",
                    "type":"file_changed",
                    "subject":"Changed",
                    "message":{"unexpected":true}
                  },
                  {"activity_id":41,"subject":"duplicate"}
                ]}}
                """.trimIndent(),
            ),
            requestedLimit = 50,
        )

        assertEquals(listOf(42L, 41L), page.activities.map(NextcloudActivity::id))
        val nullable = page.activities.first()
        assertEquals("nextcloud", nullable.app)
        assertEquals("", nullable.type)
        assertEquals("Nextcloud activity", nullable.subject)
        assertNull(nullable.message)
        assertEquals("73", nullable.objectId)
        assertFalse(page.hasMore)
        assertEquals(41L, page.nextSince)
    }

    @Test
    fun `page requests remain GET only and use bounded opaque cursors`() = runBlocking {
        var observed: NextcloudApiRequest? = null
        val page = loadNextcloudActivityPage(since = 80, limit = 2) { request ->
            observed = request
            response(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
                  {"activity_id":79,"app":"files","type":"file_changed","subject":"One"},
                  {"activity_id":78,"app":"files","type":"file_changed","subject":"Two"}
                ]}}
                """.trimIndent(),
            )
        }

        assertEquals(NextcloudApiMethod.GET, observed?.method)
        assertEquals(
            mapOf("since" to "80", "limit" to "2", "sort" to "desc"),
            observed?.queryParameters,
        )
        assertTrue(observed?.ocsApiRequest == true)
        assertEquals(78L, page.nextSince)
        assertTrue(page.hasMore)
        assertFailsWith<IllegalArgumentException> { buildNextcloudActivityPageRequest(since = -1) }
        Unit
    }

    @Test
    fun `refresh state preserves the last good list and paging de-duplicates overlap`() {
        val firstPage = NextcloudActivityPage(
            activities = listOf(activity(9), activity(8)),
            nextSince = 8,
            hasMore = true,
        )
        val loaded = ActivityTimelineState().applyActivityRefresh(firstPage)
        val refreshing = loaded.beginActivityRefresh()

        assertEquals(listOf(9L, 8L), refreshing.activities.map(NextcloudActivity::id))
        assertTrue(refreshing.refreshing)
        assertTrue(refreshing.initialized)

        val loadingMore = loaded.beginNextActivityPage()
        val merged = loadingMore.applyNextActivityPage(
            NextcloudActivityPage(
                activities = listOf(activity(8), activity(7)),
                nextSince = 7,
                hasMore = false,
            ),
        )
        assertEquals(listOf(9L, 8L, 7L), merged.activities.map(NextcloudActivity::id))
        assertFalse(merged.loadingMore)
        assertFalse(merged.hasMore)

        val failedRefresh = loaded.beginActivityRefresh().failActivityLoad("temporary")
        assertEquals(loaded.activities, failedRefresh.activities)
        assertEquals("temporary", failedRefresh.error)
    }

    @Test
    fun `media message file and general events receive stable semantic routes`() {
        val message = activity(1, app = "spreed", type = "chat_message", objectType = "chat")
        val media = activity(2, app = "files", type = "file_created", objectType = "files", objectName = "clip.mp4")
        val file = activity(3, app = "files_sharing", type = "shared", objectType = "files")
        val general = activity(4, app = "activity", type = "status")

        assertEquals(NextcloudActivitySemantic.Message, message.semantic())
        assertEquals(ActivityNotificationDestination.Talk, message.dynamicNotificationPlan("account").destination)
        assertEquals(NextcloudActivitySemantic.Media, media.semantic())
        assertEquals(ActivityNotificationDestination.Media, media.dynamicNotificationPlan("account").destination)
        assertEquals(NextcloudActivitySemantic.File, file.semantic())
        assertEquals(ActivityNotificationDestination.Files, file.dynamicNotificationPlan("account").destination)
        assertEquals(NextcloudActivitySemantic.General, general.semantic())
        assertEquals(ActivityNotificationDestination.Activity, general.dynamicNotificationPlan("account").destination)
        assertTrue(message.dynamicNotificationPlan("account").groupKey.startsWith("activity:talk:"))
    }

    @Test
    fun `empty and not-modified pages are safe terminal results`() {
        listOf(204, 304).forEach { status ->
            val page = parseNextcloudActivityPage(
                NextcloudApiResponse(status, byteArrayOf(), null, null),
            )
            assertTrue(page.activities.isEmpty())
            assertNull(page.nextSince)
            assertFalse(page.hasMore)
        }
    }

    private fun activity(
        id: Long,
        app: String = "activity",
        type: String = "status",
        objectType: String? = null,
        objectName: String? = null,
    ) = NextcloudActivity(
        id = id,
        app = app,
        type = type,
        subject = "Update $id",
        message = null,
        objectType = objectType,
        objectId = null,
        objectName = objectName,
        link = null,
        icon = null,
        dateTime = null,
    )

    private fun response(body: String, status: Int = 200) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json; charset=utf-8",
        etag = null,
    )
}
