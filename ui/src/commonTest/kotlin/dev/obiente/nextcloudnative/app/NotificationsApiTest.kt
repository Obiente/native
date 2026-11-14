package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsApiTest {
    @Test
    fun `requests use the documented v2 routes with OCS headers`() {
        val list = notificationsListRequest()
        assertEquals(NextcloudApiMethod.GET, list.method)
        assertEquals("/ocs/v2.php/apps/notifications/api/v2/notifications", list.relativePath)
        assertTrue(list.ocsApiRequest)
        assertEquals(NextcloudApiCachePolicy.RefreshNetwork, list.cachePolicy)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, notificationsListRequest(verification = true).cachePolicy)

        val dismiss = dismissNotificationRequest(61)
        assertEquals(NextcloudApiMethod.DELETE, dismiss.method)
        assertEquals("/ocs/v2.php/apps/notifications/api/v2/notifications/61", dismiss.relativePath)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, dismiss.cachePolicy)
        assertFailsWith<IllegalArgumentException> { dismissNotificationRequest(0) }

        val all = dismissAllNotificationsRequest()
        assertEquals(NextcloudApiMethod.DELETE, all.method)
        assertEquals("/ocs/v2.php/apps/notifications/api/v2/notifications", all.relativePath)

        assertEquals("/ocs/v2.php/cloud/capabilities", notificationsCapabilitiesRequest().relativePath)
        listOf(list, dismiss, all, notificationsCapabilitiesRequest()).forEach { it.requireSafe() }
    }

    @Test
    fun `a valid list renders rich subjects and keeps advertised actions`() {
        val result = parseNotificationsList(ocs(DOCUMENTED_NOTIFICATION))
        val loaded = assertIs<NotificationsListResult.Loaded>(result)
        assertFalse(loaded.truncated)
        val notification = loaded.notifications.single()
        assertEquals(61L, notification.id)
        assertEquals("files_sharing", notification.app)
        assertEquals(1_076_599_161L, notification.publishedAtEpochSeconds)
        assertEquals("You received test as a remote share from User One", notification.subject)
        assertNull(notification.message)
        assertEquals("https://cloud.example.test/index.php/apps/files_sharing/pending", notification.link)
        assertEquals(
            listOf(
                NotificationAction("Accept", SHARE_ACTION_LINK, NotificationActionMethod.Post, primary = true),
                NotificationAction("Decline", SHARE_ACTION_LINK, NotificationActionMethod.Delete, primary = false),
            ),
            notification.actions,
        )
        assertTrue(notification.actions[1].destructive)
        assertFalse(notification.actions[0].destructive)
    }

    @Test
    fun `missing optional fields fall back without inventing values`() {
        val loaded = assertIs<NotificationsListResult.Loaded>(
            parseNotificationsList(
                ocs(
                    """[
                    {"notification_id":3,"subject":"Plain subject","subjectRich":"","subjectRichParameters":[],"message":"","link":"","actions":[]},
                    {"notification_id":2},
                    {"app":"files","subject":"No identity"},
                    {"notification_id":"7","subject":"String identity"}
                    ]""",
                ),
            ),
        )
        assertEquals(listOf(3L, 2L), loaded.notifications.map { it.id })
        val plain = loaded.notifications[0]
        assertEquals("Plain subject", plain.subject)
        assertEquals(NOTIFICATIONS_APP_ID, plain.app)
        assertNull(plain.publishedAtEpochSeconds)
        assertNull(plain.message)
        assertNull(plain.link)
        assertEquals("New notification", loaded.notifications[1].subject)
    }

    @Test
    fun `rich text with an unknown placeholder uses the plain subject`() {
        val loaded = assertIs<NotificationsListResult.Loaded>(
            parseNotificationsList(
                ocs(
                    """[{"notification_id":4,"subject":"Plain","subjectRich":"Shared {file} with {missing}",
                    "subjectRichParameters":{"file":{"type":"file","id":"1","name":"Report.pdf"}},
                    "messageRich":"Line one\n\nLine {two} and {literal braces}","messageRichParameters":{"two":{"type":"highlight","id":"2","name":"two"}}}]""",
                ),
            ),
        )
        val notification = loaded.notifications.single()
        assertEquals("Plain", notification.subject)
        assertEquals("Line one\n\nLine two and {literal braces}", notification.message)
    }

    @Test
    fun `adversarial text and links are normalized or dropped`() {
        val loaded = assertIs<NotificationsListResult.Loaded>(
            parseNotificationsList(
                ocs(
                    """[{"notification_id":5,"subject":"  Hello\u0000\u0007   world \t ","link":"javascript:alert(1)",
                    "actions":[
                      {"label":"Run","link":"javascript:alert(1)","type":"POST","primary":true},
                      {"label":"Patch","link":"https://cloud.example.test/ocs/v2.php/x","type":"PATCH"},
                      {"label":"","link":"https://cloud.example.test/ocs/v2.php/x","type":"POST"},
                      {"label":"Web","link":"https://cloud.example.test/index.php/apps/x","type":"web"}
                    ]}]""",
                ),
            ),
        )
        val notification = loaded.notifications.single()
        assertEquals("Hello world", notification.subject)
        assertNull(notification.link)
        assertEquals(listOf("Web"), notification.actions.map { it.label })
        assertEquals(NotificationActionMethod.Web, notification.actions.single().method)
    }

    @Test
    fun `malformed responses are rejected as protocol failures`() {
        fun failure(response: NextcloudApiResponse) =
            assertFailsWith<NotificationsProtocolException> { parseNotificationsList(response) }.failure
        assertEquals(NotificationsProtocolFailure.Malformed, failure(response("not json")))
        assertEquals(NotificationsProtocolFailure.Malformed, failure(response("""{"data":[]}""")))
        assertEquals(NotificationsProtocolFailure.Malformed, failure(response("""{"ocs":{"data":[]}}""")))
        assertEquals(NotificationsProtocolFailure.Malformed, failure(ocs("""{"notification_id":1}""")))
        assertEquals(NotificationsProtocolFailure.Malformed, failure(response(ocsBody("[]"), contentType = "text/html")))
        assertEquals(
            NotificationsProtocolFailure.Malformed,
            failure(response("""{"ocs":{"meta":{"status":"failure","statuscode":997},"data":[]}}""")),
        )
    }

    @Test
    fun `oversized bodies and lists are bounded explicitly`() {
        val tooLarge = NextcloudApiResponse(200, ByteArray((MAX_NOTIFICATIONS_RESPONSE_BYTES + 1).toInt()), "application/json", null)
        assertEquals(
            NotificationsProtocolFailure.Oversized,
            assertFailsWith<NotificationsProtocolException> { parseNotificationsList(tooLarge) }.failure,
        )
        val entries = (1..MAX_NOTIFICATION_ENTRIES + 50).joinToString(prefix = "[", postfix = "]") { id ->
            """{"notification_id":$id,"subject":"N $id","datetime":"2026-01-01T00:00:00+00:00"}"""
        }
        val loaded = assertIs<NotificationsListResult.Loaded>(parseNotificationsList(ocs(entries)))
        assertTrue(loaded.truncated)
        assertEquals(MAX_NOTIFICATION_ENTRIES, loaded.notifications.size)
        assertEquals((MAX_NOTIFICATION_ENTRIES + 50).toLong(), loaded.notifications.first().id, "Newest IDs are kept.")
    }

    @Test
    fun `no content means no app can notify this account`() {
        assertEquals(
            NotificationsListResult.NoNotifierApps,
            parseNotificationsList(NextcloudApiResponse(204, ByteArray(0), null, null)),
        )
    }

    @Test
    fun `capabilities decide availability and the supported version`() {
        fun capabilities(notifications: String) = parseNotificationsCapabilities(
            ocs("""{"version":{"major":31},"capabilities":{"core":{}$notifications}}"""),
        )
        val available = assertIs<NotificationsCapabilitiesResult.Available>(
            capabilities(""","notifications":{"ocs-endpoints":["list","get","delete","delete-all","icons"]}"""),
        ).capabilities
        assertTrue(available.canList)
        assertTrue(available.canDismiss)
        assertTrue(available.canDismissAll)

        val listOnly = assertIs<NotificationsCapabilitiesResult.Available>(
            capabilities(""","notifications":{"ocs-endpoints":["list","get"]}"""),
        ).capabilities
        assertFalse(listOnly.canDismiss)
        assertFalse(listOnly.canDismissAll)

        assertEquals(NotificationsCapabilitiesResult.NotEnabled, capabilities(""))
        assertEquals(NotificationsCapabilitiesResult.Unsupported, capabilities(""","notifications":{"ocs-endpoints":["get"]}"""))
        assertEquals(NotificationsCapabilitiesResult.Unsupported, capabilities(""","notifications":[]"""))
        assertFailsWith<NotificationsProtocolException> { capabilities(""","notifications":"yes"""") }
        assertFailsWith<NotificationsProtocolException> { parseNotificationsCapabilities(ocs("""{"version":{}}""")) }
    }

    @Test
    fun `actions become requests only for same-account links`() {
        val post = NotificationAction("Accept", SHARE_ACTION_LINK, NotificationActionMethod.Post, primary = true)
        val request = assertNotNullRequest(notificationActionRequest(SERVER, post))
        assertEquals(NextcloudApiMethod.POST, request.method)
        assertEquals("/ocs/v1.php/apps/files_sharing/api/v1/remote_shares/13", request.relativePath)
        assertTrue(request.ocsApiRequest)
        assertNull(request.body)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, request.cachePolicy)

        val subpath = notificationActionRequest(
            "https://example.test/cloud",
            post.copy(link = "https://example.test/cloud/index.php/apps/spreed/api/v1/x"),
        )
        assertEquals("/index.php/apps/spreed/api/v1/x", subpath?.relativePath)
        assertFalse(subpath!!.ocsApiRequest)

        assertNull(notificationActionRequest(SERVER, post.copy(link = "https://other.example.test/ocs/v2.php/x")))
        assertNull(notificationActionRequest("https://example.test/cloud", post.copy(link = "https://example.test/other/ocs/v2.php/x")))
        assertNull(notificationActionRequest(SERVER, post.copy(link = "$SHARE_ACTION_LINK?format=xml")))
        assertNull(notificationActionRequest(SERVER, post.copy(link = "https://cloud.example.test/ocs/../remote.php/dav")))
        assertNull(notificationActionRequest(SERVER, post.copy(method = NotificationActionMethod.Web)))
    }

    @Test
    fun `OCS v1 failures inside HTTP 200 are not reported as success`() {
        assertTrue(notificationWriteSucceeded(NextcloudApiResponse(200, ByteArray(0), null, null)))
        assertTrue(notificationWriteSucceeded(response("""{"ocs":{"meta":{"statuscode":100},"data":[]}}""")))
        assertTrue(notificationWriteSucceeded(response("""{"ocs":{"meta":{"statuscode":200},"data":[]}}""")))
        assertFalse(notificationWriteSucceeded(response("""{"ocs":{"meta":{"statuscode":404},"data":[]}}""")))
        assertFalse(notificationWriteSucceeded(response("", status = 403)))
    }

    @Test
    fun `an embedded OCS server error is ambiguous and an embedded refusal is not`() {
        assertTrue(notificationWriteFailedAmbiguously(response("""{"ocs":{"meta":{"statuscode":996},"data":[]}}""")))
        assertTrue(notificationWriteFailedAmbiguously(response("""{"ocs":{"meta":{"statuscode":500},"data":[]}}""")))
        assertFalse(notificationWriteFailedAmbiguously(response("""{"ocs":{"meta":{"statuscode":404},"data":[]}}""")))
        assertFalse(notificationWriteFailedAmbiguously(response("""{"ocs":{"meta":{"statuscode":997},"data":[]}}""")))
        assertFalse(notificationWriteFailedAmbiguously(response("""{"ocs":{"meta":{"statuscode":100},"data":[]}}""")))
        assertFalse(notificationWriteFailedAmbiguously(response("", status = 403)))
    }

    private fun assertNotNullRequest(request: NextcloudApiRequest?): NextcloudApiRequest =
        kotlin.test.assertNotNull(request, "The same-account action should become a request.")

    companion object {
        const val SERVER = "https://cloud.example.test"
        const val SHARE_ACTION_LINK = "https://cloud.example.test/ocs/v1.php/apps/files_sharing/api/v1/remote_shares/13"

        /** The documented example, with synthetic hosts. */
        val DOCUMENTED_NOTIFICATION = """[{
            "notification_id": 61,
            "app": "files_sharing",
            "user": "admin",
            "datetime": "2004-02-12T15:19:21+00:00",
            "object_type": "remote_share",
            "object_id": "13",
            "subject": "You received admin@localhost as a remote share from test",
            "subjectRich": "You received {share} as a remote share from {user}",
            "subjectRichParameters": {
                "share": {"type": "pending-federated-share", "id": "1", "name": "test"},
                "user": {"type": "user", "id": "test1", "name": "User One", "server": "https://cloud.example.test"}
            },
            "message": "",
            "messageRich": "",
            "messageRichParameters": [],
            "link": "https://cloud.example.test/index.php/apps/files_sharing/pending",
            "icon": "https://cloud.example.test/img/icon.svg",
            "shouldNotify": true,
            "actions": [
                {"label": "Accept", "link": "$SHARE_ACTION_LINK", "type": "POST", "primary": true},
                {"label": "Decline", "link": "$SHARE_ACTION_LINK", "type": "DELETE", "primary": false}
            ]
        }]"""

        fun ocsBody(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

        fun ocs(data: String) = response(ocsBody(data))

        fun response(body: String, status: Int = 200, contentType: String? = "application/json; charset=utf-8") =
            NextcloudApiResponse(status, body.encodeToByteArray(), contentType, null)
    }
}
