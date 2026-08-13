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
            mapOf("since" to "80", "limit" to "2", "sort" to "desc", "previews" to "true"),
            observed?.queryParameters,
        )
        assertEquals("/ocs/v2.php/apps/activity/api/v2/activity", observed?.relativePath)
        assertTrue(observed?.ocsApiRequest == true)
        assertEquals(78L, page.nextSince)
        assertTrue(page.hasMore)
        assertFailsWith<IllegalArgumentException> { buildNextcloudActivityPageRequest(since = -1) }
        assertEquals(
            "/ocs/v2.php/apps/activity/api/v2/activity/self",
            buildNextcloudActivityPageRequest(filterId = "self").relativePath,
        )
        assertFailsWith<IllegalArgumentException> {
            buildNextcloudActivityPageRequest(filterId = "../../admin")
        }
        Unit
    }

    @Test
    fun `file previews retain only bounded file identities needed by the native preview loader`() {
        val page = parseNextcloudActivityPage(
            response(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[{
                  "activity_id":42,"app":"files","type":"file_changed","subject":"Changed photo.jpg",
                  "object_type":"files","object_id":73,"object_name":"/Photos/photo.jpg",
                  "previews":[
                    {"fileId":-1,"filename":"invalid.jpg"},
                    {"fileId":73,"filename":"photo.jpg","mimeType":"image/jpeg","isMimeTypeIcon":false,"source":"https://ignored.invalid/secret"}
                  ]
                }]}}
                """.trimIndent(),
            ),
        )

        assertEquals(73L, page.activities.single().preview?.fileId)
        assertEquals("photo.jpg", page.activities.single().preview?.filename)
        assertFalse(page.activities.single().preview?.isMimeTypeIcon == true)
    }

    @Test
    fun `file previews accept the documented numeric source identity`() {
        val page = parseNextcloudActivityPage(
            response(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[{
                  "activity_id":43,"app":"files","type":"file_changed","subject":"Changed report.pdf",
                  "object_type":"files","object_id":74,"object_name":"/Documents/report.pdf",
                  "previews":[{"source":74,"filename":"report.pdf","mimeType":"application/pdf"}]
                }]}}
                """.trimIndent(),
            ),
        )

        assertEquals(74L, page.activities.single().preview?.fileId)
    }

    @Test
    fun `server contributed filters are validated de duplicated and ordered`() {
        val filters = parseNextcloudActivityFilters(
            response(
                """
                {"ocs":{"meta":{"status":"ok","statuscode":200},"data":[
                  {"filter_id":"calendar","name":"Calendar","priority":70,"icon":"https://fixture.invalid/calendar.svg"},
                  {"filter_id":"all","name":"All activities","priority":0},
                  {"filter_id":"../../admin","name":"Unsafe","priority":1},
                  {"filter_id":"calendar","name":"Duplicate","priority":2},
                  {"id":"by","name":"By others","priority":2}
                ]}}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("all", "by", "calendar"), filters.map(NextcloudActivityFilterOption::id))
        assertEquals(
            "/ocs/v2.php/apps/activity/api/v2/activity/filters",
            buildNextcloudActivityFiltersRequest().relativePath,
        )
    }

    @Test
    fun `file-like activity without an authoritative path does not target the files root`() {
        val action = activity(
            id = 44,
            app = "custom_app",
            type = "file_changed",
            objectName = "report.pdf",
        ).activityOpenAction(setOf("files", "custom_app"), "https://cloud.example.test")

        assertEquals("custom_app", action?.appId)
        assertNull(action?.filesParentPath)
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
    fun `activity settings handoffs remain on the account origin`() {
        assertEquals(
            "https://cloud.example.test/index.php/settings/user/notifications",
            activitySettingsUrl("https://cloud.example.test/", ActivitySettingsDestination.Notifications),
        )
        assertEquals(
            "https://cloud.example.test/index.php/apps/activity",
            activitySettingsUrl("https://cloud.example.test", ActivitySettingsDestination.RssFeed),
        )
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
