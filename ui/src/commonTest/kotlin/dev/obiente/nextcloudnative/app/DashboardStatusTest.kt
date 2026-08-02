package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardStatusTest {
    @Test
    fun `dashboard requests are bounded GETs with opaque cursors`() {
        val widgets = dashboardWidgetsRequest()
        val items = dashboardItemsRequest(mapOf("calendar" to "2026-07-23T12:00:00Z"))

        assertEquals(NextcloudApiMethod.GET, widgets.method)
        assertEquals("/ocs/v2.php/apps/dashboard/api/v1/widgets", widgets.relativePath)
        assertTrue(widgets.ocsApiRequest)
        assertTrue(widgets.maximumResponseBytes <= 4L * 1024L * 1024L)
        assertEquals(NextcloudApiMethod.GET, items.method)
        assertNull(items.contentType)
        assertNull(items.body)
        assertEquals("2026-07-23T12:00:00Z", items.queryParameters["sinceIds[calendar]"])
        assertFailsWith<IllegalArgumentException> {
            dashboardItemsRequest(mapOf("../widget" to "cursor"))
        }
        assertFailsWith<IllegalArgumentException> {
            dashboardItemsRequest(mapOf("calendar" to "bad\u0000cursor"))
        }
    }

    @Test
    fun `widget descriptors retain ordering API versions and safe actions`() {
        val widgets = parseDashboardWidgets(
            response(
                """
                {
                  "calendar": {
                    "id": "calendar",
                    "title": "Upcoming events",
                    "order": 20,
                    "icon_url": "/apps/dashboard/icons/calendar.svg",
                    "icon_class": "icon-calendar",
                    "widget_url": "/apps/dashboard/",
                    "item_api_versions": [1, 2],
                    "item_icons_round": true,
                    "reload_interval": 60,
                    "buttons": [{"type":"more","text":"Open calendar","link":"/apps/calendar/"}]
                  },
                  "notes": {
                    "id": "notes",
                    "title": "Notes",
                    "order": 10,
                    "item_api_versions": [1],
                    "item_icons_round": false,
                    "reload_interval": 0,
                    "buttons": []
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("notes", "calendar"), widgets.map(NativeDashboardWidget::id))
        val calendar = widgets.last()
        assertEquals(setOf(1, 2), calendar.itemApiVersions)
        assertEquals(60, calendar.reloadIntervalSeconds)
        assertTrue(calendar.itemIconsRound)
        assertEquals("/apps/calendar/", calendar.actions.single().link)
        assertNull(widgets.first().reloadIntervalSeconds)
    }

    @Test
    fun `unsafe dashboard actions are rejected`() {
        listOf(
            "javascript:alert(1)",
            "//outside.example.test/path",
            "https://user:password@example.test/path",
            "/apps/dashboard/../admin",
        ).forEach { link ->
            assertFailsWith<IllegalArgumentException> {
                parseDashboardWidgets(
                    response(
                        """
                        {"unsafe":{
                          "id":"unsafe","title":"Unsafe","order":1,
                          "item_api_versions":[1],"item_icons_round":false,"reload_interval":0,
                          "buttons":[{"type":"open","text":"Open","link":"$link"}]
                        }}
                        """.trimIndent(),
                    ),
                )
            }
        }
    }

    @Test
    fun `dashboard items retain per widget cursors without accepting unknown groups`() {
        val widgets = parseDashboardWidgets(
            response(
                """
                {"calendar":{
                  "id":"calendar","title":"Calendar","order":1,
                  "item_api_versions":[1],"item_icons_round":false,"reload_interval":0,"buttons":[]
                }}
                """.trimIndent(),
            ),
        )
        val items = parseDashboardItems(
            response(
                """
                {"calendar":[
                  {"title":"First","subtitle":"Soon","link":"/apps/calendar/one","iconUrl":"","overlayIconUrl":"","sinceId":"cursor-1"},
                  {"title":"Second","subtitle":"","link":"https://calendar.example.test/two","iconUrl":"","overlayIconUrl":"","sinceId":"cursor-2"}
                ]}
                """.trimIndent(),
            ),
            widgets,
        )
        val snapshot = NativeDashboardSnapshot(widgets, items)

        assertEquals(2, items.getValue("calendar").size)
        assertEquals("cursor-1", snapshot.latestSinceIds["calendar"])
        assertNull(items.getValue("calendar").first().iconUrl)
        assertFailsWith<IllegalArgumentException> {
            parseDashboardItems(
                response("""{"unknown":[]}"""),
                widgets,
            )
        }
    }

    @Test
    fun `dashboard items keep distinct entries that share a timestamp cursor`() {
        val widgets = parseDashboardWidgets(
            response(
                """
                {"activity":{
                  "id":"activity","title":"Recent activity","order":1,
                  "item_api_versions":[1],"item_icons_round":false,"reload_interval":0,"buttons":[]
                }}
                """.trimIndent(),
            ),
        )

        val items = parseDashboardItems(
            response(
                """
                {"activity":[
                  {"title":"File shared","subtitle":"Reports","link":"/apps/files/one","iconUrl":"","overlayIconUrl":"","sinceId":"2026-08-02T09:30:00Z"},
                  {"title":"Comment added","subtitle":"Planning","link":"/apps/files/two","iconUrl":"","overlayIconUrl":"","sinceId":"2026-08-02T09:30:00Z"}
                ]}
                """.trimIndent(),
            ),
            widgets,
        )
        val snapshot = NativeDashboardSnapshot(widgets, items)

        assertEquals(listOf("File shared", "Comment added"), items.getValue("activity").map(NativeDashboardItem::title))
        assertEquals("2026-08-02T09:30:00Z", snapshot.latestSinceIds["activity"])
    }

    @Test
    fun `status capability and current status preserve presence and expiry shape`() {
        val capabilities = parseUserStatusCapabilities(
            response(
                """
                {"capabilities":{"user_status":{
                  "enabled":true,"restore":true,"supports_emoji":true,"supports_busy":true
                }}}
                """.trimIndent(),
            ),
        )
        val status = parseCurrentUserStatus(
            response(
                """
                {
                  "userId":"person-1","status":"away","message":"Heads down","icon":"🧠",
                  "messageId":"focus","clearAt":1784829600,
                  "messageIsPredefined":false,"statusIsUserDefined":true
                }
                """.trimIndent(),
            ),
        )

        assertTrue(capabilities.enabled)
        assertTrue(capabilities.restore)
        assertTrue(capabilities.supportsEmoji)
        assertTrue(capabilities.supportsBusy)
        assertEquals(NativeUserPresence.Away, status.presence)
        assertEquals(1784829600, status.clearAtEpochSeconds)
        assertTrue(status.expiresWithin(1784826000, 3601))
        assertFalse(status.messageIsPredefined)
        assertTrue(status.statusIsUserDefined)
    }

    @Test
    fun `predefined statuses accept current object and null expiry shapes`() {
        val statuses = parsePredefinedStatuses(
            response(
                """
                [
                  {"id":"meeting","message":"In a meeting","icon":"📅","clearAt":{"type":"period","time":3600}},
                  {"id":"none","message":"Available","icon":null,"clearAt":null}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(2, statuses.size)
        assertEquals(NativeStatusExpiryOption("period", 3600), statuses.first().clearAt)
        assertNull(statuses.last().clearAt)
    }

    @Test
    fun `status write planning is capability gated encoded and never executed`() {
        val capabilities = NativeUserStatusCapabilities(
            enabled = true,
            restore = true,
            supportsEmoji = true,
            supportsBusy = false,
        )
        val edit = NativeUserStatusEdit.CustomMessage(
            message = "Focus & ship",
            icon = "🧠",
            clearAtEpochSeconds = 2_000,
        )
        val request = planUserStatusEdit(edit, capabilities, nowEpochSeconds = 1_000)

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals(
            "/ocs/v2.php/apps/user_status/api/v1/user_status/message/custom",
            request.relativePath,
        )
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        val body = requireNotNull(request.body).decodeToString()
        assertTrue("message=Focus%20%26%20ship" in body)
        assertTrue("statusIcon=%F0%9F%A7%A0" in body)
        assertTrue("clearAt=2000" in body)
        assertFalse("Focus & ship" in edit.toString())
        assertFailsWith<IllegalArgumentException> {
            planUserStatusEdit(
                NativeUserStatusEdit.Presence(NativeUserPresence.Busy),
                capabilities,
                nowEpochSeconds = 1_000,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            planUserStatusEdit(
                NativeUserStatusEdit.CustomMessage("Expired", null, 999),
                capabilities,
                nowEpochSeconds = 1_000,
            )
        }
    }

    @Test
    fun `status restore and clear plans remain explicit`() {
        val capabilities = NativeUserStatusCapabilities(true, true, true, true)
        val restore = planUserStatusEdit(
            NativeUserStatusEdit.Restore("backup-1"),
            capabilities,
            nowEpochSeconds = 1_000,
        )
        val clear = planUserStatusEdit(
            NativeUserStatusEdit.ClearMessage,
            capabilities,
            nowEpochSeconds = 1_000,
        )

        assertEquals(NextcloudApiMethod.DELETE, restore.method)
        assertTrue(restore.relativePath.endsWith("/revert/backup-1"))
        assertEquals(NextcloudApiMethod.DELETE, clear.method)
        assertTrue(clear.relativePath.endsWith("/message"))
        assertNull(clear.body)
    }

    @Test
    fun `dashboard status cache is short lived and account private`() {
        val cache = DashboardStatusMemoryCache(ttlSeconds = 60)
        val first = NextcloudSession("https://cloud.example.test", "first", "secret")
        val rotated = first.copy(appPassword = "rotated")
        val second = first.copy(loginName = "second")
        val widget = NativeDashboardWidget(
            id = "calendar",
            title = "Calendar",
            order = 1,
            iconUrl = null,
            iconClass = null,
            widgetUrl = null,
            itemApiVersions = setOf(1),
            itemIconsRound = false,
            reloadIntervalSeconds = null,
            actions = emptyList(),
        )
        val dashboard = NativeDashboardSnapshot(listOf(widget), mapOf("calendar" to emptyList()))

        cache.store(first, dashboard, status = null, nowEpochSeconds = 1_000)

        assertEquals(dashboard, cache.get(rotated, 1_030)?.dashboard)
        assertNull(cache.get(second, 1_030))
        assertNull(cache.get(first, 1_061))
    }

    private fun response(data: String, status: Int = 200): NextcloudApiResponse = NextcloudApiResponse(
        status = status,
        body = """
            {"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}
        """.trimIndent().encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
