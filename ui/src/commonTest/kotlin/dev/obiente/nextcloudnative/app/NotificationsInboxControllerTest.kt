package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsInboxControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val account = deriveNextcloudAccountId(SERVER, "synthetic-user")

    @AfterTest
    fun cancelScope() = scope.cancel()

    private class FakeNotificationsServer {
        var endpoints = """["list","get","delete","delete-all"]"""
        var capabilitiesIncludeNotifications = true
        val listed = linkedMapOf<Long, String>()
        var listFailure: Exception? = null
        var listGate: CompletableDeferred<Unit>? = null
        var deleteStatus = 200
        var deleteFailure: Exception? = null
        var actionStatus = 200
        var actionOcsStatus = 100
        var actionFailure: Exception? = null
        /** Applies a successful DELETE to the listed notifications. */
        var deleteTakesEffect = true
        val requests = mutableListOf<NextcloudApiRequest>()

        fun list(vararg ids: Long) {
            listed.clear()
            ids.forEach { id -> listed[id] = notification(id) }
        }

        suspend fun execute(request: NextcloudApiRequest): NextcloudApiResponse {
            requests += request
            return when {
                request.relativePath == NOTIFICATIONS_CAPABILITIES_PATH -> response(
                    200,
                    ocs(
                        if (capabilitiesIncludeNotifications) {
                            """{"capabilities":{"notifications":{"ocs-endpoints":$endpoints}}}"""
                        } else {
                            """{"capabilities":{"core":{}}}"""
                        },
                    ),
                )
                request.relativePath == NOTIFICATIONS_API_PATH && request.method == NextcloudApiMethod.GET -> {
                    val snapshot = listed.values.joinToString(prefix = "[", postfix = "]")
                    listGate?.let { gate -> listGate = null; gate.await() }
                    listFailure?.let { throw it }
                    response(200, ocs(snapshot))
                }
                request.relativePath.startsWith(NOTIFICATIONS_API_PATH) && request.method == NextcloudApiMethod.DELETE -> {
                    val id = request.relativePath.removePrefix(NOTIFICATIONS_API_PATH).removePrefix("/").toLongOrNull()
                    if (deleteTakesEffect && deleteStatus == 200) {
                        if (id == null) listed.clear() else listed.remove(id)
                    }
                    deleteFailure?.let { throw it }
                    response(deleteStatus, ocs("[]"))
                }
                else -> {
                    actionFailure?.let { throw it }
                    response(actionStatus, """{"ocs":{"meta":{"statuscode":$actionOcsStatus},"data":[]}}""")
                }
            }
        }

        fun count(method: NextcloudApiMethod, path: String? = null) =
            requests.count { it.method == method && (path == null || it.relativePath == path) }

        companion object {
            fun notification(id: Long, actions: String = "[]") =
                """{"notification_id":$id,"app":"files","datetime":"2026-01-0${(id % 9) + 1}T00:00:00+00:00",""" +
                    """"subject":"Synthetic notification $id","actions":$actions}"""

            fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""

            fun response(status: Int, body: String) =
                NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
        }
    }

    private fun controller(
        server: FakeNotificationsServer,
        cache: NotificationsInboxMemoryCache? = null,
        accountId: NextcloudAccountId = account,
        attempts: NotificationActionAttemptStore? = null,
    ) = NotificationsInboxController(
        scope = scope,
        accountId = accountId,
        serverUrl = SERVER,
        execute = server::execute,
        cache = cache,
        parseDispatcher = Dispatchers.Unconfined,
        actionAttempts = attempts,
    )

    private class MemoryActionAttempts(private val failSave: Boolean = false) : NotificationActionAttemptStore {
        var ids = emptySet<Long>()
        val savedWhile = mutableListOf<Int>()
        var writes: () -> Int = { 0 }

        override suspend fun load(): Set<Long> = ids

        override suspend fun save(notificationIds: Set<Long>) {
            check(!failSave) { "Synthetic storage is full." }
            savedWhile += writes()
            ids = notificationIds
        }
    }

    @Test
    fun `an action with an unknown result stays off after the inbox is recreated`() {
        val actions = """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply {
            listed[4] = FakeNotificationsServer.notification(4, actions)
            actionFailure = IllegalStateException("synthetic reset")
        }
        val store = MemoryActionAttempts().apply { writes = { server.count(NextcloudApiMethod.POST) } }
        val first = controller(server, attempts = store).apply { start() }
        first.runAction(4, first.state.notification(4)!!.actions.single())
        assertEquals(setOf(4L), store.ids)
        assertEquals(0, store.savedWhile.first(), "The attempt is on record before the request leaves.")

        // The activity was recreated or the app relaunched: a new controller, the same device record.
        server.actionFailure = null
        val later = controller(server, attempts = store).apply { start() }
        assertEquals(setOf(4L), later.state.ambiguousActionIds)
        later.runAction(4, later.state.notification(4)!!.actions.single())
        assertEquals(1, server.count(NextcloudApiMethod.POST), "The action is not repeated.")

        server.listed.remove(4)
        later.refresh()
        assertTrue(store.ids.isEmpty(), "A notification that is gone no longer needs its record.")
    }

    @Test
    fun `a full record refuses a new action instead of forgetting an unresolved one`() {
        val actions = """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply { listed[4] = FakeNotificationsServer.notification(4, actions) }
        val full = (1000L until 1000L + MAX_RECORDED_ACTION_ATTEMPTS).toSet()
        val store = MemoryActionAttempts().apply { ids = full }
        // Their notifications are still listed, so none of the stored attempts is resolved.
        full.forEach { id -> server.listed[id] = FakeNotificationsServer.notification(id, "[]") }
        val controller = controller(server, attempts = store).apply { start() }
        controller.runAction(4, controller.state.notification(4)!!.actions.single())
        assertEquals(0, server.count(NextcloudApiMethod.POST))
        assertEquals(full, store.ids, "No unresolved attempt is dropped to make room.")
    }

    @Test
    fun `a definite answer closes the record and an unrecorded action is not sent`() {
        val actions = """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply { listed[4] = FakeNotificationsServer.notification(4, actions) }
        val store = MemoryActionAttempts()
        val controller = controller(server, attempts = store).apply { start() }
        controller.runAction(4, controller.state.notification(4)!!.actions.single())
        assertEquals(1, server.count(NextcloudApiMethod.POST))
        assertTrue(store.ids.isEmpty())

        val unwritable = controller(server, attempts = MemoryActionAttempts(failSave = true)).apply { start() }
        unwritable.runAction(4, unwritable.state.notification(4)!!.actions.single())
        assertEquals(1, server.count(NextcloudApiMethod.POST), "Nothing is sent without a record.")
        assertTrue(unwritable.state.notice.orEmpty().contains("could not keep a record"))
    }

    private fun ids(controller: NotificationsInboxController) = controller.state.notifications.orEmpty().map { it.id }

    @Test
    fun `start reads capabilities and the list and counts it for the badge`() {
        val server = FakeNotificationsServer().apply { list(3, 2, 1) }
        val controller = controller(server)
        controller.start()
        val state = controller.state
        assertFalse(state.refreshing)
        assertIs<NotificationsAvailability.Available>(state.availability)
        assertEquals(3, state.badgeCount)
        assertNull(state.failure)
        controller.start()
        assertEquals(1, server.count(NextcloudApiMethod.GET, NOTIFICATIONS_API_PATH), "start() reads once.")
    }

    @Test
    fun `a disabled app is reported without reading the list`() {
        val server = FakeNotificationsServer().apply { capabilitiesIncludeNotifications = false; list(1) }
        val controller = controller(server)
        controller.start()
        assertEquals(NotificationsAvailability.NotEnabled, controller.state.availability)
        assertEquals(0, controller.state.badgeCount)
        assertEquals(0, server.count(NextcloudApiMethod.GET, NOTIFICATIONS_API_PATH))
    }

    @Test
    fun `a server without the v2 list endpoint is unsupported`() {
        val server = FakeNotificationsServer().apply { endpoints = """["get"]""" }
        val controller = controller(server)
        controller.start()
        assertEquals(NotificationsAvailability.Unsupported, controller.state.availability)
    }

    @Test
    fun `the account cache paints first and stays scoped to its account`() {
        val cache = NotificationsInboxMemoryCache()
        val server = FakeNotificationsServer().apply { list(5, 4) }
        controller(server, cache).start()
        val gate = CompletableDeferred<Unit>()
        server.listGate = gate
        val reopened = controller(server, cache)
        assertTrue(reopened.state.showingCachedCopy)
        assertEquals(listOf(5L, 4L), ids(reopened))
        server.list(5)
        reopened.start()
        assertTrue(reopened.state.refreshing)
        assertEquals(listOf(5L, 4L), ids(reopened), "The cached copy stays visible while it refreshes.")
        gate.complete(Unit)
        assertFalse(reopened.state.showingCachedCopy)
        assertEquals(listOf(5L), ids(reopened))

        val other = controller(server, cache, deriveNextcloudAccountId(SERVER, "other-user"))
        assertNull(other.state.notifications, "Another account never sees this account's cache.")
    }

    @Test
    fun `a retired account's cache is purged and no longer written`() {
        val gate = AccountPrivateMemoryGate()
        val cache = NotificationsInboxMemoryCache(gate)
        val server = FakeNotificationsServer().apply { list(1) }
        val controller = controller(server, cache)
        controller.start()
        gate.retireAccount(account.storageKey) { cache.purgeRetiredAccount(account.storageKey) }
        controller.refresh()
        assertNull(controller(server, cache).state.notifications)
    }

    @Test
    fun `a replaced refresh is cancelled and cannot apply its response`() {
        val server = FakeNotificationsServer().apply { list(1) }
        val controller = controller(server)
        controller.start()
        val slow = CompletableDeferred<Unit>()
        server.listGate = slow
        server.list(9, 8, 7)
        controller.refresh()
        server.list(2)
        controller.refresh()
        assertEquals(listOf(2L), ids(controller))
        slow.complete(Unit)
        assertEquals(listOf(2L), ids(controller), "The cancelled read must not restore the older list.")
        assertFalse(controller.state.refreshing)
    }

    @Test
    fun `cancellation is not reported as a failure`() {
        val server = FakeNotificationsServer().apply { list(1) }
        val controller = controller(server)
        controller.start()
        server.listFailure = CancellationException("synthetic cancellation")
        controller.refresh()
        assertNull(controller.state.failure)
        assertFalse(controller.state.refreshing)
        assertEquals(listOf(1L), ids(controller))
    }

    @Test
    fun `a failed refresh keeps the notifications already shown`() {
        val server = FakeNotificationsServer().apply { list(2, 1) }
        val controller = controller(server)
        controller.start()
        server.listFailure = IllegalStateException("synthetic network failure")
        controller.refresh()
        assertEquals(NotificationsFailureKind.Offline, controller.state.failure?.kind)
        assertEquals(listOf(2L, 1L), ids(controller))
        assertFalse(controller.state.refreshing)
    }

    @Test
    fun `a slow read that started before a dismissal cannot restore the dismissed notification`() {
        val server = FakeNotificationsServer().apply { list(2, 1) }
        val controller = controller(server)
        controller.start()
        val slow = CompletableDeferred<Unit>()
        server.listGate = slow
        controller.refresh()
        controller.dismiss(2)
        assertEquals(listOf(1L), ids(controller))
        slow.complete(Unit)
        assertEquals(listOf(1L), ids(controller), "The earlier read is older than the verification read.")
        assertTrue(controller.state.pendingIds.isEmpty())
    }

    @Test
    fun `a dismissal is verified with a fresh read`() {
        val server = FakeNotificationsServer().apply { list(2, 1) }
        val controller = controller(server)
        controller.start()
        controller.select(2)
        controller.dismiss(2)
        assertEquals(listOf(1L), ids(controller))
        assertNull(controller.state.selectedId)
        assertNull(controller.state.notice)
        val afterDelete = server.requests.dropWhile { it.method != NextcloudApiMethod.DELETE }
        assertEquals("$NOTIFICATIONS_API_PATH/2", afterDelete.first().relativePath)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, afterDelete[1].cachePolicy)
    }

    @Test
    fun `an accepted dismissal that is still listed is reported, not hidden`() {
        val server = FakeNotificationsServer().apply { list(2, 1); deleteTakesEffect = false }
        val controller = controller(server)
        controller.start()
        controller.dismiss(2)
        assertEquals(listOf(2L, 1L), ids(controller))
        assertNotNull(controller.state.notice)
    }

    @Test
    fun `an unknown dismissal result is checked and never retried automatically`() {
        val server = FakeNotificationsServer().apply {
            list(2, 1)
            deleteTakesEffect = false
            deleteFailure = IllegalStateException("synthetic timeout")
        }
        val controller = controller(server)
        controller.start()
        controller.dismiss(2)
        assertEquals(1, server.count(NextcloudApiMethod.DELETE))
        assertEquals(listOf(2L, 1L), ids(controller))
        assertEquals("The notification was not dismissed. You can try again.", controller.state.notice)
        assertTrue(controller.state.canDismiss(controller.state.notification(2)!!), "A verified state allows a new attempt.")
    }

    @Test
    fun `an unknown result that cannot be verified pauses writes until a later read`() {
        val server = FakeNotificationsServer().apply { list(2, 1); deleteFailure = IllegalStateException("synthetic timeout") }
        val controller = controller(server)
        controller.start()
        server.listFailure = IllegalStateException("synthetic offline")
        controller.dismiss(2)
        assertEquals(setOf(2L), controller.state.unconfirmedIds)
        assertFalse(controller.state.canDismiss(controller.state.notification(2)!!))
        assertFalse(controller.state.canDismissAll)
        controller.dismiss(2)
        assertEquals(1, server.count(NextcloudApiMethod.DELETE), "Writes stay off while the result is unknown.")

        server.listFailure = null
        controller.refresh()
        assertTrue(controller.state.unconfirmedIds.isEmpty())
        assertEquals(listOf(1L), ids(controller), "The server applied the first request.")
    }

    @Test
    fun `a rejected dismissal keeps the notification and explains why`() {
        val server = FakeNotificationsServer().apply { list(2, 1); deleteStatus = 403 }
        val controller = controller(server)
        controller.start()
        val readsBefore = server.count(NextcloudApiMethod.GET, NOTIFICATIONS_API_PATH)
        controller.dismiss(2)
        assertEquals(listOf(2L, 1L), ids(controller))
        assertTrue(controller.state.notice!!.startsWith("Couldn't dismiss the notification."))
        assertEquals(readsBefore, server.count(NextcloudApiMethod.GET, NOTIFICATIONS_API_PATH))
    }

    @Test
    fun `dismissing is unavailable when the server does not advertise it`() {
        val server = FakeNotificationsServer().apply { list(1); endpoints = """["list"]""" }
        val controller = controller(server)
        controller.start()
        controller.dismiss(1)
        controller.requestDismissAll()
        assertNull(controller.state.confirmation)
        assertEquals(0, server.count(NextcloudApiMethod.DELETE))
    }

    @Test
    fun `dismiss all waits for confirmation and verifies the result`() {
        val server = FakeNotificationsServer().apply { list(3, 2, 1) }
        val controller = controller(server)
        controller.start()
        controller.requestDismissAll()
        assertEquals(NotificationsConfirmation.DismissAll, controller.state.confirmation)
        assertEquals(0, server.count(NextcloudApiMethod.DELETE))
        controller.cancelConfirmation()
        assertNull(controller.state.confirmation)
        assertEquals(0, server.count(NextcloudApiMethod.DELETE))

        controller.requestDismissAll()
        controller.confirm()
        assertEquals(1, server.count(NextcloudApiMethod.DELETE, NOTIFICATIONS_API_PATH))
        assertEquals(emptyList(), ids(controller))
        assertFalse(controller.state.dismissingAll)
        assertNull(controller.state.notice)
    }

    @Test
    fun `destructive actions need confirmation and others run once`() {
        val actions = """[
            {"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true},
            {"label":"Decline","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"DELETE","primary":false}
        ]"""
        val server = FakeNotificationsServer().apply { listed[4] = FakeNotificationsServer.notification(4, actions) }
        val controller = controller(server)
        controller.start()
        val notification = controller.state.notification(4)!!
        val (accept, decline) = notification.actions

        controller.runAction(4, decline)
        assertIs<NotificationsConfirmation.Action>(controller.state.confirmation)
        assertEquals(0, server.count(NextcloudApiMethod.DELETE))
        controller.cancelConfirmation()

        controller.runAction(4, accept)
        assertEquals(1, server.count(NextcloudApiMethod.POST, "/ocs/v2.php/apps/sample/api/v1/invites/4"))
        assertEquals("The server accepted \"Accept\".", controller.state.notice)

        controller.runAction(4, decline)
        controller.confirm()
        assertEquals(1, server.count(NextcloudApiMethod.DELETE, "/ocs/v2.php/apps/sample/api/v1/invites/4"))
    }

    @Test
    fun `a server error inside a successful answer is an unknown result`() {
        val actions = """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply {
            listed[4] = FakeNotificationsServer.notification(4, actions)
            actionOcsStatus = 996
        }
        val controller = controller(server)
        controller.start()
        val accept = controller.state.notification(4)!!.actions.single()
        controller.runAction(4, accept)
        controller.runAction(4, accept)
        assertEquals(1, server.count(NextcloudApiMethod.POST), "An action that may have taken effect is not repeated.")
        assertEquals(setOf(4L), controller.state.ambiguousActionIds)
    }

    @Test
    fun `an action with an unknown result is not offered again`() {
        val actions = """[{"label":"Accept","link":"$SERVER/ocs/v2.php/apps/sample/api/v1/invites/4","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply {
            listed[4] = FakeNotificationsServer.notification(4, actions)
            actionFailure = IllegalStateException("synthetic reset")
        }
        val controller = controller(server)
        controller.start()
        val accept = controller.state.notification(4)!!.actions.single()
        controller.runAction(4, accept)
        controller.runAction(4, accept)
        assertEquals(1, server.count(NextcloudApiMethod.POST))
        assertEquals(setOf(4L), controller.state.ambiguousActionIds)
        assertFalse(controller.state.canRunActions(controller.state.notification(4)!!))
        assertTrue(controller.state.notice!!.startsWith("Couldn't confirm whether \"Accept\" reached the server."))
    }

    @Test
    fun `actions outside the account are never requested`() {
        val actions = """[{"label":"Accept","link":"https://elsewhere.example.test/ocs/v2.php/x","type":"POST","primary":true}]"""
        val server = FakeNotificationsServer().apply { listed[4] = FakeNotificationsServer.notification(4, actions) }
        val controller = controller(server)
        controller.start()
        controller.runAction(4, controller.state.notification(4)!!.actions.single())
        assertEquals(0, server.count(NextcloudApiMethod.POST))
        assertTrue(controller.state.pendingIds.isEmpty())
        assertNotNull(controller.state.notice)
    }

    private companion object {
        const val SERVER = "https://cloud.example.test"
    }
}
