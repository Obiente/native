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
        val widgets = dashboardWidgetsRequest(NextcloudApiCachePolicy.ForceNetwork)
        val items = dashboardItemsRequest(mapOf("calendar" to "2026-07-23T12:00:00Z"))
        val v2Items = dashboardItemsRequest(
            apiVersion = DashboardItemApiVersion.V2,
            widgetIds = setOf("calendar", "activity"),
            sinceIds = mapOf("calendar" to "cursor-2"),
        )

        assertEquals(NextcloudApiMethod.GET, widgets.method)
        assertEquals("/ocs/v2.php/apps/dashboard/api/v1/widgets", widgets.relativePath)
        assertTrue(widgets.ocsApiRequest)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, widgets.cachePolicy)
        assertTrue(widgets.maximumResponseBytes <= 4L * 1024L * 1024L)
        assertEquals(NextcloudApiMethod.GET, items.method)
        assertEquals(NextcloudApiCachePolicy.PreferCache, items.cachePolicy)
        assertNull(items.contentType)
        assertNull(items.body)
        assertEquals("2026-07-23T12:00:00Z", items.queryParameters["sinceIds[calendar]"])
        assertEquals("/ocs/v2.php/apps/dashboard/api/v2/widget-items", v2Items.relativePath)
        assertEquals("activity", v2Items.queryParameters["widgets[0]"])
        assertEquals("calendar", v2Items.queryParameters["widgets[1]"])
        assertEquals("cursor-2", v2Items.queryParameters["sinceIds[calendar]"])
        assertFailsWith<IllegalArgumentException> {
            dashboardItemsRequest(mapOf("../widget" to "cursor"))
        }
        assertFailsWith<IllegalArgumentException> {
            dashboardItemsRequest(mapOf("calendar" to "bad\u0000cursor"))
        }
        assertFailsWith<IllegalArgumentException> {
            dashboardItemsRequest(
                apiVersion = DashboardItemApiVersion.V2,
                widgetIds = setOf("calendar"),
                sinceIds = mapOf("activity" to "cursor"),
            )
        }
    }

    @Test
    fun `dashboard item request planning prefers v2 and skips widgets without an item API`() {
        val plans = dashboardItemsRequestPlans(
            widgets = listOf(
                widget("both", setOf(1, 2)),
                widget("v2-only", setOf(2)),
                widget("v1-only", setOf(1)),
                widget("embedded", emptySet()),
            ),
            sinceIds = mapOf(
                "both" to "both-cursor",
                "v1-only" to "v1-cursor",
                "embedded" to "ignored-cursor",
            ),
        )

        assertEquals(
            listOf(DashboardItemApiVersion.V2, DashboardItemApiVersion.V2, DashboardItemApiVersion.V1),
            plans.map { it.apiVersion },
        )
        assertEquals(setOf("both"), plans.first().widgetIds)
        assertEquals("both-cursor", plans.first().request.queryParameters["sinceIds[both]"])
        assertEquals(setOf("v1-only"), plans.last().widgetIds)
        assertEquals("v1-cursor", plans.last().request.queryParameters["sinceIds[v1-only]"])
        assertFalse(plans.any { plan -> "embedded" in plan.widgetIds })
    }

    @Test
    fun `only missing Dashboard routes are treated as an unavailable optional app`() {
        assertTrue(isDashboardApiUnavailable(response("not found", status = 404)))
        assertTrue(
            isDashboardApiUnavailable(
                NextcloudApiResponse(
                    status = 200,
                    body = """{"ocs":{"meta":{"statuscode":404},"data":[]}}""".encodeToByteArray(),
                    contentType = "application/json",
                    etag = null,
                ),
            ),
        )
        assertFalse(isDashboardApiUnavailable(response("unauthorized", status = 401)))
        assertFalse(isDashboardApiUnavailable(response("broken", status = 500)))
        assertFalse(isDashboardApiUnavailable(response("not-json")))
    }

    @Test
    fun `v2 plans fall back only when every requested widget supports v1`() {
        val compatible = dashboardItemsRequestPlans(listOf(widget("calendar", setOf(1, 2)))).single()
        val v2Only = dashboardItemsRequestPlans(listOf(widget("calendar", setOf(2)))).single()

        val fallback = compatible.v1FallbackRequest(listOf(widget("calendar", setOf(1, 2))))

        assertEquals("/ocs/v2.php/apps/dashboard/api/v1/widget-items", fallback?.relativePath)
        assertEquals(setOf("calendar"), compatible.widgetIds)
        assertNull(v2Only.v1FallbackRequest(listOf(widget("calendar", setOf(2)))))
    }

    @Test
    fun `dashboard response budget bounds isolated widget requests`() {
        val budget = DashboardResponseBudget(totalBytes = 1_000L)
        val first = budget.reserve(maximumBytes = 600L)
        val second = budget.reserve(maximumBytes = 600L)

        assertEquals(600L, first)
        assertEquals(400L, second)
        assertEquals(0L, budget.remainingBytes)
        budget.releaseUnused(reservedBytes = first, responseBytes = 250L)
        assertEquals(350L, budget.remainingBytes)
        val failedReservation = budget.reserve(maximumBytes = 600L)
        assertEquals(350L, failedReservation)
        budget.releaseFailed(failedReservation)
        assertEquals(350L, budget.remainingBytes)
        assertEquals(350L, budget.reserve(maximumBytes = 600L))
        assertEquals(0L, budget.reserve(maximumBytes = 600L))
        assertFailsWith<IllegalArgumentException> {
            DashboardResponseBudget(totalBytes = 0L)
        }
    }

    @Test
    fun `dashboard response budget refunds only proven zero-body failures`() {
        val zeroBodyBudget = DashboardResponseBudget(totalBytes = 1_000L)
        val zeroBodyReservation = zeroBodyBudget.reserve(maximumBytes = 600L)
        zeroBodyBudget.settleFailedRead(
            zeroBodyReservation,
            NextcloudApiReadFailure(responseBodyMayHaveStarted = false, cause = IllegalStateException("offline")),
        )
        assertEquals(1_000L, zeroBodyBudget.remainingBytes)

        val ambiguousBudget = DashboardResponseBudget(totalBytes = 1_000L)
        val ambiguousReservation = ambiguousBudget.reserve(maximumBytes = 600L)
        ambiguousBudget.settleFailedRead(
            ambiguousReservation,
            NextcloudApiReadFailure(responseBodyMayHaveStarted = true, cause = IllegalStateException("truncated")),
        )
        assertEquals(400L, ambiguousBudget.remainingBytes)

        val parsingBudget = DashboardResponseBudget(totalBytes = 1_000L)
        val parsingReservation = parsingBudget.reserve(maximumBytes = 600L)
        parsingBudget.settleFailedRead(parsingReservation, IllegalArgumentException("invalid JSON"))
        assertEquals(400L, parsingBudget.remainingBytes)
    }

    @Test
    fun `initial dashboard reads may use persisted cache while explicit refresh forces network`() {
        val initialWidgets = dashboardWidgetsRequest()
        val initialItems = dashboardItemsRequestPlans(listOf(widget("calendar", setOf(2)))).single().request
        val refreshedWidgets = dashboardWidgetsRequest(NextcloudApiCachePolicy.RefreshNetwork)
        val refreshedItems = dashboardItemsRequestPlans(
            widgets = listOf(widget("calendar", setOf(2))),
            cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
        ).single().request

        assertEquals(NextcloudApiCachePolicy.PreferCache, initialWidgets.cachePolicy)
        assertEquals(NextcloudApiCachePolicy.PreferCache, initialItems.cachePolicy)
        assertEquals(NextcloudApiCachePolicy.RefreshNetwork, refreshedWidgets.cachePolicy)
        assertEquals(NextcloudApiCachePolicy.RefreshNetwork, refreshedItems.cachePolicy)
        assertEquals(DASHBOARD_ITEM_RESPONSE_LIMIT_BYTES, refreshedItems.maximumResponseBytes)
        assertTrue(
            DASHBOARD_ITEM_RESPONSE_LIMIT_BYTES * 128 > DASHBOARD_REFRESH_RESPONSE_BUDGET_BYTES,
            "The shared aggregate budget must be stricter than multiplying every per-widget ceiling.",
        )
        assertEquals(4, MAX_CONCURRENT_DASHBOARD_ITEM_REQUESTS)
    }

    @Test
    fun `expired process cache does not discard the dashboard still displayed during refresh`() {
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        val snapshot = NativeDashboardSnapshot(listOf(widget("calendar", setOf(2))), emptyMap())
        val cache = DashboardStatusMemoryCache(ttlSeconds = 60L)
        cache.store(session, snapshot, status = null, nowEpochSeconds = 100L)

        val expired = cache.get(session, nowEpochSeconds = 161L)

        assertNull(expired)
        assertEquals(snapshot, retainedDashboardRefreshSnapshot(expired, snapshot))
    }

    @Test
    fun `unsupported advertised dashboard APIs remain distinguishable from embedded widgets`() {
        val widgets = listOf(
            widget("future", setOf(3)),
            widget("mixed-future", setOf(2, 3)),
            widget("embedded", emptySet()),
        )

        assertEquals(setOf("future"), unsupportedDashboardWidgetIds(widgets))
        val snapshot = mergeDashboardItemFetchResults(
            widgets = widgets,
            previousSnapshot = null,
            results = listOf(
                DashboardItemsFetchResult.Loaded(
                    setOf("mixed-future"),
                    DashboardItemsPayload(mapOf("mixed-future" to emptyList())),
                ),
            ),
            unsupportedWidgetIds = setOf("future"),
        )
        assertEquals(setOf("future"), snapshot.unsupportedWidgetIds)
        assertTrue(snapshot.failedWidgetIds.isEmpty())
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
    fun `empty v1 item collection is a valid empty result`() {
        assertEquals(
            emptyMap(),
            parseDashboardItems(response("[]"), listOf(widget("calendar", setOf(1)))),
        )
    }

    @Test
    fun `v2 dashboard items retain official empty state messages`() {
        val widgets = listOf(widget("calendar", setOf(2)))
        val payload = parseDashboardItemsV2(
            response(
                """
                {"calendar":{
                  "items":[
                    {"title":"Planning","subtitle":"Tomorrow","link":"/apps/calendar/one","iconUrl":"","overlayIconUrl":"","sinceId":"cursor-v2"}
                  ],
                  "emptyContentMessage":"No upcoming events",
                  "halfEmptyContentMessage":"Your schedule is clear after this"
                }}
                """.trimIndent(),
            ),
            widgets,
        )

        assertEquals("Planning", payload.itemsByWidget.getValue("calendar").single().title)
        assertEquals("No upcoming events", payload.emptyContentMessagesByWidget["calendar"])
        assertEquals("Your schedule is clear after this", payload.halfEmptyContentMessagesByWidget["calendar"])
        assertFailsWith<IllegalStateException> {
            parseDashboardItemsV2(response("""{"calendar":[]}"""), widgets)
        }
    }

    @Test
    fun `mixed dashboard item failures preserve cached sections without hiding successful widgets`() {
        val calendar = widget("calendar", setOf(2))
        val activity = widget("activity", setOf(1))
        val previous = NativeDashboardSnapshot(
            widgets = listOf(calendar, activity),
            itemsByWidget = mapOf(
                "calendar" to listOf(item("calendar", "Saved event", "old-calendar")),
                "activity" to listOf(item("activity", "Old activity", "old-activity")),
            ),
            emptyContentMessagesByWidget = mapOf("calendar" to "No saved events"),
        )
        val merged = mergeDashboardItemFetchResults(
            widgets = listOf(calendar, activity),
            previousSnapshot = previous,
            results = listOf(
                DashboardItemsFetchResult.Failed(setOf("calendar")),
                DashboardItemsFetchResult.Loaded(
                    widgetIds = setOf("activity"),
                    payload = DashboardItemsPayload(
                        itemsByWidget = mapOf(
                            "activity" to listOf(item("activity", "New activity", "new-activity")),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Saved event", merged.itemsByWidget.getValue("calendar").single().title)
        assertEquals("New activity", merged.itemsByWidget.getValue("activity").single().title)
        assertEquals(setOf("calendar"), merged.failedWidgetIds)
        assertEquals("No saved events", merged.emptyContentMessagesByWidget["calendar"])
    }

    @Test
    fun `pending widgets retain cached content while completed peers publish`() {
        val calendar = widget("calendar", setOf(2))
        val activity = widget("activity", setOf(2))
        val previous = NativeDashboardSnapshot(
            widgets = listOf(calendar, activity),
            itemsByWidget = mapOf(
                "calendar" to listOf(item("calendar", "Saved event", "calendar-old")),
                "activity" to listOf(item("activity", "Old activity", "activity-old")),
            ),
        )
        val partial = mergeDashboardItemFetchResults(
            widgets = listOf(calendar, activity),
            previousSnapshot = previous,
            results = listOf(
                DashboardItemsFetchResult.Loaded(
                    widgetIds = setOf("activity"),
                    payload = DashboardItemsPayload(
                        mapOf("activity" to listOf(item("activity", "New activity", "activity-new"))),
                    ),
                ),
            ),
            loadingWidgetIds = setOf("calendar"),
        )

        assertEquals("Saved event", partial.itemsByWidget.getValue("calendar").single().title)
        assertEquals("New activity", partial.itemsByWidget.getValue("activity").single().title)
        assertEquals(setOf("calendar"), partial.loadingWidgetIds)
        assertTrue(partial.failedWidgetIds.isEmpty())
    }

    @Test
    fun `successful empty item result replaces cached content`() {
        val calendar = widget("calendar", setOf(2))
        val previous = NativeDashboardSnapshot(
            widgets = listOf(calendar),
            itemsByWidget = mapOf("calendar" to listOf(item("calendar", "Old event", "old"))),
        )
        val merged = mergeDashboardItemFetchResults(
            widgets = listOf(calendar),
            previousSnapshot = previous,
            results = listOf(
                DashboardItemsFetchResult.Loaded(
                    widgetIds = setOf("calendar"),
                    payload = DashboardItemsPayload(
                        itemsByWidget = emptyMap(),
                        emptyContentMessagesByWidget = mapOf("calendar" to "No events"),
                    ),
                ),
            ),
        )

        assertTrue(merged.itemsByWidget.getValue("calendar").isEmpty())
        assertEquals("No events", merged.emptyContentMessagesByWidget["calendar"])
        assertTrue(merged.failedWidgetIds.isEmpty())
    }

    @Test
    fun `omitted requested widget is a failed result rather than an empty success`() {
        val omitted = dashboardItemsFetchResult(
            requestedWidgetIds = setOf("calendar"),
            payload = DashboardItemsPayload(emptyMap()),
        )
        val validEmpty = dashboardItemsFetchResult(
            requestedWidgetIds = setOf("calendar"),
            payload = DashboardItemsPayload(mapOf("calendar" to emptyList())),
        )

        assertTrue(omitted is DashboardItemsFetchResult.Failed)
        assertTrue(validEmpty is DashboardItemsFetchResult.Loaded)
    }

    @Test
    fun `dashboard diagnostics expose only bounded stage and cache state`() {
        val diagnostic = dashboardLoadFailureDiagnostic(
            stage = "widget_items_v2",
            code = "DASHBOARD_WIDGET_ITEMS_V2_FAILED",
            cachedAvailable = true,
            severity = SupportDiagnosticSeverity.Warning,
        )

        assertEquals(SupportDiagnosticComponent.App, diagnostic.component)
        assertEquals("dashboard.load", diagnostic.operation)
        assertEquals("DASHBOARD_WIDGET_ITEMS_V2_FAILED", diagnostic.code)
        assertEquals(
            mapOf("stage" to "widget_items_v2", "cached_available" to "true"),
            diagnostic.fields.associate { it.name to it.value },
        )
        assertNull(diagnostic.message)
        assertNull(diagnostic.exception)
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

    private fun widget(id: String, versions: Set<Int>): NativeDashboardWidget = NativeDashboardWidget(
        id = id,
        title = id,
        order = 1,
        iconUrl = null,
        iconClass = null,
        widgetUrl = null,
        itemApiVersions = versions,
        itemIconsRound = false,
        reloadIntervalSeconds = null,
        actions = emptyList(),
    )

    private fun item(widgetId: String, title: String, sinceId: String): NativeDashboardItem =
        NativeDashboardItem(
            widgetId = widgetId,
            title = title,
            subtitle = null,
            link = null,
            iconUrl = null,
            overlayIconUrl = null,
            sinceId = sinceId,
        )
}
