package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardLinkPolicyTest {
    private val origin = "http://cloud.example.test:8080"
    private val policy = DashboardLinkPolicy.forAccount("$origin/nextcloud")

    @Test
    fun `approved HTTP widgets and both item APIs retain their links`() = runBlocking {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val loaded = acquireDashboardWidgets(
            cachedAvailable = false,
            linkPolicy = policy,
            executeResponse = { widgetsResponse("$origin/apps/calendar", "$origin/icon.svg") },
            onDiagnostic = diagnostics::add,
        )
        assertTrue(loaded.authoritative)
        assertTrue(diagnostics.isEmpty())
        val widget = loaded.widgets.single()
        assertEquals("$origin/apps/calendar", widget.widgetUrl)
        assertEquals(widget.widgetUrl, widget.actions.single().link)
        assertEquals("$origin/icon.svg", widget.iconUrl)
        for (version in DashboardItemApiVersion.entries) {
            val item = version.parsePayload(
                itemsResponse(version, "$origin/apps/calendar/event", "$origin/icon.svg"), loaded.widgets, policy,
            ).itemsByWidget.getValue("calendar").single()
            assertEquals("$origin/apps/calendar/event", item.link)
            assertEquals("$origin/icon.svg", item.iconUrl)
            assertEquals(item.iconUrl, item.overlayIconUrl)
        }
    }

    @Test
    fun `TLS defaults and HTTPS accounts reject plain HTTP widgets and items`() {
        val widgets = parseDashboardWidgets(widgetsResponse("/apps/calendar"))
        assertFailsWith<IllegalArgumentException> { parseDashboardWidgets(widgetsResponse("$origin/path")) }
        assertFailsWith<IllegalArgumentException> {
            parseDashboardItems(itemsResponse(DashboardItemApiVersion.V1, "$origin/path"), widgets)
        }
        assertFailsWith<IllegalArgumentException> {
            parseDashboardItemsV2(itemsResponse(DashboardItemApiVersion.V2, "$origin/path"), widgets)
        }
        for (restricted in listOf(DashboardLinkPolicy.TlsOnly, DashboardLinkPolicy.forAccount("https://cloud.example.test:8080"))) {
            assertFalse(restricted.accepts("$origin/path"))
            assertTrue(restricted.accepts("https://outside.example.test/path"))
            assertTrue(restricted.accepts("/apps/calendar"))
        }
    }

    @Test
    fun `HTTP origin matching normalizes case and default ports without widening consent`() {
        assertTrue(policy.accepts("HTTP://CLOUD.EXAMPLE.TEST:8080/path?query=value#fragment"))
        assertTrue(DashboardLinkPolicy.forAccount("http://cloud.example.test:80/base").accepts("http://cloud.example.test/path"))
        assertTrue(DashboardLinkPolicy.forAccount("http://cloud.example.test").accepts("http://cloud.example.test:80/path"))
        assertTrue(DashboardLinkPolicy.forAccount("http://[2001:db8::1]:8080").accepts("http://[2001:db8::1]:8080/path"))
        for (link in listOf(
            "http://outside.example.test:8080/path", "http://cloud.example.test/path",
            "http://cloud.example.test:8081/path", "http://cloud.example.test.evil.test:8080/path",
            "http://cloud.example.test:8080@evil.test/path", "http://user@cloud.example.test:8080/path",
            "http://cloud.example.test:8080\\@evil.test/path", "http://cloud.example.test:8080:/path",
            "http://[2001:db8::2]:8080/path", "//cloud.example.test:8080/path", "javascript:alert(1)",
            "/apps/../admin", "/apps/%2e%2e/admin", "/apps/./admin", "/has space", "/has\u0000control",
            "http:///path", "https://", "https://user@cloud.example.test/path", "/" + "x".repeat(8192),
        )) {
            assertFalse(policy.accepts(link), link)
        }
        assertTrue(policy.accepts("https://outside.example.test/path"))
        assertTrue(policy.accepts("/apps/calendar"))
        assertFalse(DashboardLinkPolicy.forAccount("not a server URL").accepts("$origin/path"))
    }

    @Test
    fun `disallowed actionable links still fail while optional icons are omitted`() {
        val widgets = parseDashboardWidgets(widgetsResponse("/apps/calendar"))
        for (link in listOf("http://outside.example.test/path", "javascript:alert(1)", "/" + "x".repeat(8192))) {
            assertFailsWith<IllegalArgumentException> { parseDashboardWidgets(widgetsResponse(link), policy) }
            assertNull(parseDashboardWidgets(widgetsResponse("/apps/calendar", link), policy).single().iconUrl)
            for (version in DashboardItemApiVersion.entries) {
                assertFailsWith<IllegalArgumentException> {
                    version.parsePayload(itemsResponse(version, link), widgets, policy)
                }
                val item = version.parsePayload(itemsResponse(version, "/apps/calendar", link), widgets, policy)
                    .itemsByWidget.getValue("calendar").single()
                assertNull(item.iconUrl)
                assertNull(item.overlayIconUrl)
            }
        }
        assertNull(parseDashboardWidgets(widgetsResponse("/apps/calendar", "$origin/icon.svg")).single().iconUrl)
    }

    private fun widgetsResponse(link: String, icon: String = ""): NextcloudApiResponse = response(
        """{"calendar":{"id":"calendar","title":"Calendar","item_api_versions":[1,2],
        "widget_url":${JsonPrimitive(link)},"icon_url":${JsonPrimitive(icon)},
        "buttons":[{"type":"open","text":"Open","link":${JsonPrimitive(link)}}]}}""",
    )

    private fun itemsResponse(version: DashboardItemApiVersion, link: String, icon: String = ""): NextcloudApiResponse {
        val items = """[{"title":"Event","sinceId":"cursor-1","link":${JsonPrimitive(link)},
            "iconUrl":${JsonPrimitive(icon)},"overlayIconUrl":${JsonPrimitive(icon)}}]"""
        return response(when (version) {
            DashboardItemApiVersion.V1 -> """{"calendar":$items}"""
            DashboardItemApiVersion.V2 -> """{"calendar":{"items":$items}}"""
        })
    }

    private fun response(data: String): NextcloudApiResponse = NextcloudApiResponse(
        status = 200,
        body = """{"ocs":{"meta":{"statuscode":200},"data":$data}}""".encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
