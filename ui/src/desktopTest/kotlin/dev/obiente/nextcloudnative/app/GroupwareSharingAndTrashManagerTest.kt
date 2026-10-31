package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.lang.reflect.Proxy
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

class GroupwareSharingAndTrashManagerTest {
    private val session = NextcloudSession("https://groupware.invalid", "alice", "synthetic")
    private val calendar = "/remote.php/dav/calendars/alice/personal/"
    private val bob = DavSharee("principals/users/bob", "Bob", DavShareeKind.User)

    @Test
    fun aShareIsConfirmedByReadingTheShareListAgain() = runBlocking<Unit> {
        val server = FakeGroupware()
        val manager = GroupwareSharingManager(server.services, session, this)
        manager.open(GroupwareShareTarget(calendar, "Personal", publishable = true))
        settle { !manager.loading }
        manager.share(bob)
        settle { !manager.busy }
        assertNull(manager.error)
        assertEquals(listOf("principals/users/bob"), manager.state?.shares?.map { it.sharee.principal })
        assertEquals(listOf("PROPFIND", "POST", "PROPFIND"), server.methods)
    }

    @Test
    fun aLostResponseIsResolvedByTheServerStateNotGuessed() = runBlocking<Unit> {
        val server = FakeGroupware().apply { loseNextPost = true }
        val manager = GroupwareSharingManager(server.services, session, this)
        manager.open(GroupwareShareTarget(calendar, "Personal", publishable = true))
        settle { !manager.loading }
        manager.share(bob)
        settle { !manager.busy }
        assertNull(manager.error, "The share reached the server, so the re-read confirms it.")

        server.loseNextPost = true
        server.dropNextPost = true
        manager.setWritable(manager.state!!.shares.single(), true)
        settle { !manager.busy }
        assertTrue(manager.error!!.contains("unknown"), "A lost request that changed nothing is reported as unknown.")
        assertEquals(false, manager.state!!.shares.single().writable)
    }

    @Test
    fun aRefusedOrIgnoredShareKeepsTheDialogWithAReason() = runBlocking<Unit> {
        val server = FakeGroupware().apply { refusePost = 403 }
        val manager = GroupwareSharingManager(server.services, session, this)
        manager.open(GroupwareShareTarget(calendar, "Personal", publishable = true))
        settle { !manager.loading }
        manager.share(bob)
        settle { !manager.busy }
        assertTrue(manager.error!!.contains("permission"))
        assertEquals(listOf("PROPFIND", "POST"), server.methods, "A refusal needs no second read.")

        server.refusePost = null
        server.dropNextPost = true
        manager.share(bob)
        settle { !manager.busy }
        assertTrue(manager.error!!.contains("did not apply"))
    }

    @Test
    fun publishingIsConfirmedByThePublishedLink() = runBlocking<Unit> {
        val server = FakeGroupware()
        val manager = GroupwareSharingManager(server.services, session, this)
        manager.open(GroupwareShareTarget(calendar, "Personal", publishable = true))
        settle { !manager.loading }
        manager.setPublished(true)
        settle { !manager.busy }
        assertNotNull(manager.state?.publishUrl)
        manager.setPublished(false)
        settle { !manager.busy }
        assertNull(manager.state?.publishUrl)
        assertNull(manager.error)
    }

    @Test
    fun emptyingTheTrashBinReportsExactlyWhatWasNotRemoved() = runBlocking<Unit> {
        val server = FakeGroupware().apply {
            trash += "/remote.php/dav/calendars/alice/trashbin/objects/1.ics"
            trash += "/remote.php/dav/calendars/alice/trashbin/objects/2.ics"
            refuseDeleteOf = "/remote.php/dav/calendars/alice/trashbin/objects/2.ics"
        }
        val manager = CalendarTrashBinManager(server.services, session, "alice", this)
        var changes = 0
        manager.onChanged = { changes++ }
        manager.show()
        settle { !manager.loading }
        assertEquals(2, manager.contents?.items?.size)
        manager.requestEmpty()
        manager.confirmRemoval()
        settle { !manager.busy }
        assertEquals(listOf("/remote.php/dav/calendars/alice/trashbin/objects/2.ics"), manager.contents?.items?.map { it.href })
        assertTrue(manager.error!!.contains("1 of 2"))
        assertEquals(1, changes)
        assertEquals(30, manager.contents?.retentionDays)
    }

    @Test
    fun aRestoreWithALostResponseCountsWhenTheItemLeftTheTrashBin() = runBlocking<Unit> {
        val server = FakeGroupware().apply {
            trash += "/remote.php/dav/calendars/alice/trashbin/objects/7.ics"
            loseNextMove = true
        }
        val manager = CalendarTrashBinManager(server.services, session, "alice", this)
        manager.show()
        settle { !manager.loading }
        manager.restore(manager.contents!!.items.single())
        settle { !manager.busy }
        assertNull(manager.error)
        assertTrue(manager.contents!!.items.isEmpty())
        assertEquals(
            "https://groupware.invalid/remote.php/dav/calendars/alice/trashbin/restore/7.ics",
            server.destinations.single(),
        )
    }

    private suspend fun settle(done: () -> Boolean) {
        repeat(200) { yield(); if (done()) return }
        error("The change did not finish")
    }

    private class FakeGroupware {
        val methods = mutableListOf<String>()
        val shares = linkedMapOf<String, Boolean>()
        var published = false
        var loseNextPost = false
        var dropNextPost = false
        var refusePost: Int? = null
        val trash = mutableListOf<String>()
        var refuseDeleteOf: String? = null
        var loseNextMove = false
        val destinations = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val services = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "FakeGroupware"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
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
            return when (request.method) {
                "PROPFIND" -> when {
                    request.relativePath.endsWith("/trashbin/") -> xml(
                        "<d:multistatus xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.com/ns\"><d:response><d:propstat><d:prop>" +
                            "<nc:trash-bin-retention-duration>2592000</nc:trash-bin-retention-duration></d:prop></d:propstat></d:response></d:multistatus>",
                    )
                    request.relativePath == "/remote.php/dav/calendars/alice/" -> xml("<d:multistatus xmlns:d=\"DAV:\"/>")
                    else -> xml(sharingState())
                }
                "POST" -> {
                    refusePost?.let { return status(it) }
                    if (!dropNextPost) {
                        when {
                            "publish-calendar" in body && "unpublish" !in body -> published = true
                            "unpublish-calendar" in body -> published = false
                            "<oc:set>" in body -> shares["principals/users/bob"] = "<oc:read-write/>" in body
                            "<oc:remove>" in body -> shares.remove("principals/users/bob")
                        }
                    }
                    dropNextPost = false
                    if (loseNextPost) {
                        loseNextPost = false
                        throw IOException("Connection reset after the request was sent")
                    }
                    status(200)
                }
                "REPORT" -> xml(
                    "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" + trash.joinToString("") { href ->
                        "<d:response><d:href>$href</d:href><d:propstat><d:prop><c:calendar-data>BEGIN:VCALENDAR\nBEGIN:VEVENT\n" +
                            "SUMMARY:Deleted\nEND:VEVENT\nEND:VCALENDAR</c:calendar-data></d:prop></d:propstat></d:response>"
                    } + "</d:multistatus>",
                )
                "MOVE" -> {
                    destinations += request.headers.getValue("Destination")
                    trash.remove(request.relativePath)
                    if (loseNextMove) {
                        loseNextMove = false
                        throw IOException("Timed out")
                    }
                    status(201)
                }
                "DELETE" -> if (request.relativePath == refuseDeleteOf) status(403) else {
                    trash.remove(request.relativePath)
                    status(204)
                }
                else -> error("Unexpected ${request.method}")
            }
        }

        private fun sharingState(): String =
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:cs=\"http://calendarserver.org/ns/\"><d:response>" +
                "<d:propstat><d:prop><oc:invite>" + shares.entries.joinToString("") { (principal, writable) ->
                    "<oc:user><d:href>principal:$principal</d:href><oc:access>${if (writable) "<oc:read-write/>" else "<oc:read/>"}</oc:access></oc:user>"
                } + "</oc:invite>" +
                (if (published) "<cs:publish-url><d:href>https://groupware.invalid/remote.php/dav/public-calendars/x</d:href></cs:publish-url>" else "") +
                "<cs:allowed-sharing-modes><cs:can-be-shared/><cs:can-be-published/></cs:allowed-sharing-modes>" +
                "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"

        private fun xml(body: String) = NextcloudApiResponse(207, body.encodeToByteArray(), "application/xml", null)
        private fun status(code: Int) = NextcloudApiResponse(code, byteArrayOf(), null, null)
    }
}
