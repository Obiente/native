package dev.obiente.nextcloudnative.app

import androidx.compose.ui.semantics.getOrNull
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.IOException

class CalendarCollectionManagerTest {
    private val home = "/remote.php/dav/calendars/alice/"

    @Test
    fun calendarRequestBodiesAreWellFormedNamespacedXml() {
        val draft = CalendarCollectionDraft("Tom & \"Jerry\" <team>", "#2E9E5B", withTasks = true)
        val subscription = draft.copy(withTasks = false, sourceUrl = "https://example.org/feed.ics?a=1&b=<2>")
        listOf(
            calendarCollectionCreateRequest("${home}tom-abc123/", draft) to ("urn:ietf:params:xml:ns:caldav" to "mkcalendar"),
            calendarSubscriptionCreateRequest("${home}feed-abc123/", subscription.copy(sourceUrl = "https://example.org/feed.ics?a=1&b=2"))
                to ("DAV:" to "mkcol"),
            calendarCollectionUpdateRequest("${home}tom-abc123/", draft) to ("DAV:" to "propertyupdate"),
        ).forEach { (request, root) ->
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(ByteArrayInputStream(request.body!!))
            assertEquals(root.first, document.documentElement.namespaceURI)
            assertEquals(root.second, document.documentElement.localName)
            val name = document.getElementsByTagNameNS("DAV:", "displayname").item(0).textContent
            assertEquals("Tom & \"Jerry\" <team>", name)
            assertEquals("#2E9E5B", document.getElementsByTagNameNS("http://apple.com/ns/ical/", "calendar-color").item(0).textContent)
        }
    }

    @Test
    fun aCreatedCalendarIsConfirmedByTheCalendarList() = runBlocking<Unit> {
        val server = FakeCalendarHome()
        val manager = manager(server)
        var changes = 0
        manager.onChanged = { changes++ }
        manager.actions.onCreate(false)
        manager.save(CalendarCollectionDraft("Family", "#2E9E5B"))
        settle(manager)
        assertNull(manager.edit)
        assertNull(manager.error)
        assertEquals(1, changes)
        assertEquals(listOf("MKCALENDAR", "PROPFIND"), server.methods)
        assertTrue(server.calendars.keys.single().startsWith("${home}family-"))
    }

    @Test
    fun anUnknownResultThatReachedTheServerCountsOnlyOnceAndRetriesReuseTheAddress() = runBlocking<Unit> {
        val server = FakeCalendarHome(loseNextResponse = true)
        val manager = manager(server)
        manager.actions.onCreate(false)
        server.listingFails = true
        manager.save(CalendarCollectionDraft("Family", "#2E9E5B"))
        settle(manager)
        assertNotNull(manager.edit, "The dialog stays open while the result is unknown")
        assertTrue(manager.error!!.contains("unknown"))
        val firstHref = server.calendars.keys.single()

        server.listingFails = false
        manager.save(CalendarCollectionDraft("Family", "#2E9E5B"))
        settle(manager)
        assertNull(manager.edit)
        assertEquals(listOf(firstHref), server.calendars.keys.toList(), "The retry found the first calendar instead of making another")
        assertEquals(2, server.methods.count { it == "MKCALENDAR" })
    }

    @Test
    fun aRefusedChangeKeepsTheDialogWithAReason() = runBlocking<Unit> {
        val server = FakeCalendarHome().apply { calendars["${home}work/"] = "Work" to "#0082C9"; refuseWrites = 403 }
        val manager = manager(server)
        manager.actions.onManage(GroupwareCalendar("${home}work/", "Work", "#0082C9", writable = true))
        manager.save(CalendarCollectionDraft("Office", "#0082C9"))
        settle(manager)
        assertNotNull(manager.edit)
        assertEquals("You don't have permission to change this calendar.", manager.error)
        assertEquals(listOf("PROPPATCH"), server.methods, "A proven refusal needs no confirming read")
    }

    @Test
    fun renamingAndDeletingAreConfirmedAgainstTheList() = runBlocking<Unit> {
        val server = FakeCalendarHome().apply { calendars["${home}work/"] = "Work" to "#0082C9" }
        val manager = manager(server)
        val work = GroupwareCalendar("${home}work/", "Work", "#0082C9", writable = true)
        manager.actions.onManage(work)
        manager.save(CalendarCollectionDraft("Office", "#E9322D"))
        settle(manager)
        assertNull(manager.edit)
        assertEquals("Office" to "#E9322D", server.calendars["${home}work/"])

        manager.requestDelete(work)
        manager.confirmDelete()
        settle(manager)
        assertNull(manager.deleting)
        assertTrue(server.calendars.isEmpty())
    }

    @Test
    fun theCalendarListCreatesACalendarThroughItsDialog() {
        val server = FakeCalendarHome().apply { calendars["${home}work/"] = "Work" to "#0082C9" }
        var changes = 0
        nativeSceneTest(420, 760, content = {
            val manager = rememberCalendarCollectionManager(server.services,
                NextcloudSession("https://calendar.invalid", "alice", "synthetic"), "alice") { changes++ }
            androidx.compose.foundation.layout.Box {
                DesktopCalendarSourcesPane(
                    listOf(GroupwareCalendar("${home}work/", "Work", "#0082C9", writable = true)), emptySet(), emptyMap(),
                    loading = false, onCalendarVisibilityChanged = { _, _ -> }, onClose = null,
                    modifier = androidx.compose.ui.Modifier, collectionActions = manager.actions,
                )
                CalendarCollectionDialogs(manager)
            }
        }) {
            assertTrue(has("Manage Work"), "Each calendar offers its own menu")
            click("New calendar")
            click("New calendar")
            assertTrue(has("Create"))
            replaceText("", "Family")
            val blue = assertNotNull(node("Blue")).boundsInRoot
            click("Green")
            assertEquals(blue, assertNotNull(node("Blue")).boundsInRoot, "Choosing a color never moves the swatches")
            assertEquals(true, node("Green")?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected))
            capture("calendar-collection-editor")
            click("Create")
            settleUntil { changes > 0 }
            assertEquals(1, changes)
            assertTrue(server.calendars.entries.any { (href, value) -> href.startsWith("${home}family-") && value == ("Family" to "#2E9E5B") })
            assertTrue(!has("Create"), "The dialog closes once the new calendar is listed")
        }
    }

    @Test
    fun sharedCalendarsOpenOnlyTheRemovalConfirmation() = runBlocking<Unit> {
        val manager = manager(FakeCalendarHome())
        val shared = GroupwareCalendar("${home}team_shared_by_bob/", "Team", kind = GroupwareCalendarKind.Shared, ownerName = "Bob")
        manager.actions.onManage(shared)
        assertNull(manager.edit)
        assertEquals(shared, manager.deleting)
    }

    private class MemoryDraft : TextEditorDraftStore {
        var value: TextEditorDraft? = null

        override suspend fun load(): TextEditorDraft? = value

        override suspend fun save(draft: TextEditorDraft) {
            value = draft
        }

        override suspend fun remove() {
            value = null
        }
    }

    @Test
    fun aCreationWhoseResultWasLostIsFoundAgainAfterTheDialogAndTheScreenAreGone() = runBlocking<Unit> {
        val record = MemoryDraft()
        val server = FakeCalendarHome(loseNextResponse = true).apply { drafts = record; listingFails = true }
        val first = manager(server)
        first.actions.onCreate(false)
        first.save(CalendarCollectionDraft("Family", "#2E9E5B"))
        settle(first)
        assertNotNull(first.error, "The calendar was created, but neither the answer nor the list arrived.")
        val address = assertNotNull(record.value?.text, "The address is on record before the request leaves.")
        first.dismiss()

        // The screen was recreated: a new manager, the same device record.
        server.listingFails = false
        val later = manager(server)
        var changes = 0
        later.onChanged = { changes++ }
        later.actions.onCreate(false)
        later.save(CalendarCollectionDraft("Family", "#2E9E5B"))
        settle(later)
        assertEquals(listOf(address), server.calendars.keys.toList(), "No second calendar is created.")
        assertTrue(later.error.orEmpty().contains("created after all"))
        assertEquals(1, changes)
        assertNull(record.value)

        later.save(CalendarCollectionDraft("Work", "#2E9E5B"))
        settle(later)
        assertEquals(2, server.calendars.size, "With the earlier attempt resolved, a new calendar can be created.")
        assertNull(record.value, "A confirmed creation leaves no record.")
    }

    private fun CoroutineScope.manager(server: FakeCalendarHome) =
        CalendarCollectionManager(server.services, NextcloudSession("https://calendar.invalid", "alice", "synthetic"), "alice", this)

    private suspend fun settle(manager: CalendarCollectionManager) {
        repeat(100) { yield(); if (!manager.busy) return }
        error("The calendar change did not finish")
    }

    private class FakeCalendarHome(var loseNextResponse: Boolean = false) {
        val calendars = linkedMapOf<String, Pair<String, String>>()
        val methods = mutableListOf<String>()
        var listingFails = false
        var refuseWrites: Int? = null

        /** Device draft storage; null as on a platform that keeps none. */
        var drafts: TextEditorDraftStore? = null

        @Suppress("UNCHECKED_CAST")
        val services = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "FakeCalendarHome"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "textEditorDraftStore" -> drafts
                "executeGroupwareDav" -> {
                    val operation: suspend () -> Any? = { execute(args!![1] as GroupwareDavRequest) }
                    operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
                }
                else -> error("Unexpected operation ${method.name}")
            }
        } as NextcloudPlatformServices

        private fun execute(request: GroupwareDavRequest): NextcloudApiResponse {
            methods += request.method
            val body = request.body?.decodeToString().orEmpty()
            fun text(name: String) = Regex("<[a-z0-9]+:$name>(.*?)</[a-z0-9]+:$name>").find(body)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
            if (request.method == "PROPFIND") {
                if (listingFails) throw IOException("synthetic listing failure")
                return NextcloudApiResponse(207, (
                    "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:x1=\"http://apple.com/ns/ical/\">" +
                        calendars.entries.joinToString("") { (href, value) ->
                            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:displayname>${value.first}</d:displayname>" +
                                "<x1:calendar-color>${value.second}</x1:calendar-color>" +
                                "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                                "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/></c:supported-calendar-component-set>" +
                                "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        } + "</d:multistatus>"
                    ).encodeToByteArray(), "application/xml", null)
            }
            refuseWrites?.let { return NextcloudApiResponse(it, byteArrayOf(), null, null) }
            val status = when (request.method) {
                "MKCALENDAR", "MKCOL" -> if (request.relativePath in calendars) 405 else {
                    calendars[request.relativePath] = text("displayname")!! to text("calendar-color")!!
                    201
                }
                "PROPPATCH" -> {
                    calendars[request.relativePath] = text("displayname")!! to text("calendar-color")!!
                    207
                }
                "DELETE" -> if (calendars.remove(request.relativePath) != null) 204 else 404
                else -> error("Unexpected method ${request.method}")
            }
            if (loseNextResponse) {
                loseNextResponse = false
                throw IOException("synthetic lost response")
            }
            val reply = if (status == 207) {
                "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>${request.relativePath}</d:href><d:propstat><d:prop><d:displayname/>" +
                    "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"
            } else ""
            return NextcloudApiResponse(status, reply.encodeToByteArray(), "application/xml", null)
        }
    }
}
