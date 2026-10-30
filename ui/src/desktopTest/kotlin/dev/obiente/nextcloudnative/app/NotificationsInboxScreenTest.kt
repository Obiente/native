package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudNavigationRail
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationsInboxScreenTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private class SyntheticServer {
        val listed = linkedMapOf(
            2L to notification(
                2, "Ada shared Quarterly report with you", FILES_LINK,
                """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/2","type":"POST","primary":true},
                    {"label":"Decline","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/2","type":"DELETE","primary":false}]""",
            ),
            1L to notification(1, "Backup finished", null, "[]"),
        )
        val writes = mutableListOf<NextcloudApiRequest>()

        suspend fun execute(request: NextcloudApiRequest): NextcloudApiResponse = when {
            request.relativePath == NOTIFICATIONS_CAPABILITIES_PATH ->
                ok("""{"capabilities":{"notifications":{"ocs-endpoints":["list","delete","delete-all"]}}}""")
            request.method == NextcloudApiMethod.GET -> ok(listed.values.joinToString(prefix = "[", postfix = "]"))
            else -> {
                writes += request
                if (request.relativePath.startsWith("$NOTIFICATIONS_API_PATH/")) {
                    listed.remove(request.relativePath.substringAfterLast('/').toLong())
                } else if (request.relativePath == NOTIFICATIONS_API_PATH) {
                    listed.clear()
                }
                ok("[]")
            }
        }

        companion object {
            fun notification(id: Long, subject: String, link: String?, actions: String) =
                """{"notification_id":$id,"app":"files","datetime":"2026-01-0${id}T00:00:00+00:00","subject":"$subject",""" +
                    """"message":"Synthetic message $id","link":${link?.let { "\"$it\"" } ?: "\"\""},"actions":$actions}"""

            fun ok(data: String) = NextcloudApiResponse(
                200,
                """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}""".encodeToByteArray(),
                "application/json",
                null,
            )
        }
    }

    private fun controller(server: SyntheticServer) = NotificationsInboxController(
        scope = scope,
        accountId = deriveNextcloudAccountId(SERVER, "synthetic-user"),
        serverUrl = SERVER,
        execute = server::execute,
        parseDispatcher = Dispatchers.Unconfined,
    ).also { it.start() }

    @Test
    fun phoneShowsTheListThenTheDetailAndReturnsWithBack() {
        val server = SyntheticServer()
        val controller = controller(server)
        val opened = mutableListOf<String>()
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(false, false, false)) {
                NotificationsInboxScreen(controller, SERVER, onOpenLink = { opened += it }, onOpenSettings = {})
            }
        }) {
            assertTrue(has("Notifications"))
            assertTrue(has("2 notifications"))
            assertTrue(has("Backup finished"))
            assertTrue(has("Accept"), "Advertised actions are shown inline.")
            assertFalse(has("Select a notification to see it here."), "Compact layouts have no detail placeholder.")
            capture("notifications-phone-list")

            click("Backup finished")
            assertTrue(has("Back to notifications"))
            assertFalse(has("Ada shared Quarterly report with you"), "The detail replaces the list on a phone.")
            assertTrue(has("Synthetic message 1"))
            assertFalse(has("Open"), "A notification without a link has no open button.")
            capture("notifications-phone-detail")
            click("Back to notifications")
            assertTrue(has("Ada shared Quarterly report with you"))

            click("Decline")
            assertTrue(has("Decline?"), "Destructive server actions need a separate confirmation.")
            click("Cancel")
            assertTrue(server.writes.isEmpty())

            click("Dismiss all")
            assertTrue(has("Dismiss all notifications?"))
            click("Cancel")
            assertTrue(server.writes.isEmpty())
            click("Dismiss all")
            click("Dismiss all")
            settle()
            assertEquals(listOf(NOTIFICATIONS_API_PATH), server.writes.map { it.relativePath })
            assertTrue(has("You're all caught up"))
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun desktopKeepsTheListBesideTheSelectedNotification() {
        val server = SyntheticServer()
        val controller = controller(server)
        val opened = mutableListOf<String>()
        nativeSceneTest(1280, 800, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(true, true, true)) {
                NotificationsInboxScreen(controller, SERVER, onOpenLink = { opened += it }, onOpenSettings = {})
            }
        }) {
            assertTrue(has("Select a notification to see it here."))
            click("Ada shared Quarterly report with you")
            assertTrue(has("Backup finished"), "The list stays visible beside the detail.")
            assertFalse(has("Back to notifications"))
            assertTrue(has("Synthetic message 2"))
            capture("notifications-desktop-detail")

            click("Open")
            assertEquals(listOf(FILES_LINK), opened, "Links go to the shared native link routing.")

            click("Dismiss")
            settle()
            assertEquals(listOf("$NOTIFICATIONS_API_PATH/2"), server.writes.map { it.relativePath })
            assertFalse(has("Ada shared Quarterly report with you"))
            assertTrue(has("Select a notification to see it here."))
            assertTrue(has("1 notification"))
        }
    }

    @Test
    fun activityDestinationSwitchesBetweenNotificationsAndActivity() {
        val server = SyntheticServer()
        val controller = controller(server)
        val session = NextcloudSession(SERVER, "synthetic-user", "synthetic-app-password")
        nativeSceneTest(390, 844, content = {
            ActivityDestinationScreen(
                session = session,
                notifications = controller,
                activityInstalled = true,
                onOpenLink = {},
                onOpenNotificationSettings = {},
            ) { Text("Synthetic activity history") }
        }) {
            assertTrue(has("Notifications (2)"))
            assertTrue(has("Backup finished"))
            click("Activity")
            assertTrue(has("Synthetic activity history"))
            assertFalse(has("Backup finished"))
        }
    }

    @Test
    fun shellNavigationAnnouncesTheNotificationBadge() {
        assertEquals("Talk", notificationAppLabel("spreed"))
        assertEquals("Sample app", notificationAppLabel("sample_app"))
        val badges = notificationsShellBadges(3)
        assertEquals(emptyMap(), notificationsShellBadges(0))
        assertEquals("99+", notificationsShellBadges(140).getValue(NextcloudDestination.Activity).text)
        for ((width, rail) in listOf(390 to false, 900 to true)) {
            nativeSceneTest(width, 844, content = {
                if (rail) {
                    NextcloudNavigationRail(NextcloudDestination.Home, onSelected = {}, badges = badges)
                } else {
                    NextcloudBottomNavigation(NextcloudDestination.Home, onSelected = {}, badges = badges)
                }
            }) {
                // Material clears the icon slot's semantics, so the count is announced only
                // through the item's description, never as a separate "3".
                assertTrue(has("Activity, 3 notifications"))
                capture("notifications-badge-${if (rail) "rail" else "bar"}")
            }
        }
    }

    private companion object {
        const val SERVER = "https://cloud.example.test"
        const val FILES_LINK = "https://cloud.example.test/index.php/apps/files/?dir=/Synthetic"
    }
}
