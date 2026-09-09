package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class GroupwareContactsMultiGetDeletionTest {
    private val book = "/remote.php/dav/addressbooks/users/person/contacts/"

    @Test
    fun deletedMembersRetainHealthyContactsAcrossBatchesAndReportPartialResults() = runBlocking {
        val hrefs = (1..12).map { "$book$it" }
        val deleted = mapOf(hrefs[1] to 404, hrefs[11] to 410)
        val notifications = mutableListOf<Int>()
        val methods = mutableListOf<String>()
        val contacts = loadGroupwareContactsInBatches(book,
            retentionBudget = GroupwareContactRetentionBudget(maximumContacts = 10),
            onConcurrentDeletion = { notifications += it },
        ) { request ->
            methods += request.method
            when (request.method) {
                "PROPFIND" -> listing(hrefs)
                "REPORT" -> {
                    val requested = request.body!!.decodeToString().xmlElements("href").map { it.xmlText("href")!! }
                    xml(requested.reversed().joinToString("") { href ->
                        deleted[href]?.let { status(href, it) } ?: healthy(href)
                    })
                }
                else -> error("Deletion responses must not trigger individual GETs")
            }
        }
        assertEquals(listOf("PROPFIND", "REPORT", "REPORT"), methods)
        assertEquals(listOf(1, 1), notifications)
        assertEquals((hrefs - deleted.keys).toSet(), contacts.map(GroupwareContact::href).toSet())
        assertTrue(contacts.all { it.rawVCard.isEmpty() && it.etag == "\"fresh-${it.uid}\"" })
    }

    @Test
    fun allDeletedMembersProduceAnExplicitPartialEmptyResult() = runBlocking {
        val hrefs = listOf("${book}one", "${book}two")
        var deleted = 0
        val contacts = loadGroupwareContactsInBatches(book,
            onConcurrentDeletion = { deleted += it },
        ) { request ->
            when (request.method) {
                "PROPFIND" -> listing(hrefs)
                "REPORT" -> xml(status(hrefs[0], 404) + status(hrefs[1], 410))
                else -> error("Unexpected fallback")
            }
        }
        assertTrue(contacts.isEmpty())
        assertEquals(2, deleted)
    }

    @Test
    fun oversizedReportSplitsRetainDeletionCountsAndHealthyMembers() = runBlocking {
        val hrefs = (1..4).map { "$book$it" }
        var deleted = 0
        val batchSizes = mutableListOf<Int>()
        val contacts = loadGroupwareContactsInBatches(book, onConcurrentDeletion = { deleted += it }) { request ->
            if (request.method == "PROPFIND") listing(hrefs) else {
                assertEquals("REPORT", request.method)
                val requested = request.body!!.decodeToString().xmlElements("href").map { it.xmlText("href")!! }
                batchSizes += requested.size
                if (requested.size > 2) throw NextcloudResponseTooLargeException(request.maximumResponseBytes, responseStatus = 207)
                xml(requested.joinToString("") { href ->
                    if (href in setOf(hrefs[0], hrefs[2])) status(href, 404) else healthy(href)
                })
            }
        }
        assertEquals(listOf(4, 2, 2), batchSizes)
        assertEquals(2, deleted)
        assertEquals(listOf(hrefs[1], hrefs[3]), contacts.map(GroupwareContact::href))
    }

    @Test
    fun missingDuplicateUnrequestedAndFailedMembersAreNotCalledDeletions() = runBlocking {
        val one = "${book}one"
        val two = "${book}two"
        val invalidReports = listOf(
            status(one, 404),
            status(one, 404) + status(one, 410),
            healthy(one) + status(one, 404) + status(two, 410),
            healthy(one) + status("${book}unrequested", 404),
            healthy(one) + status("/remote.php/dav/addressbooks/users/other/contacts/two", 404),
            healthy(one) + "<d:response><d:href>$two</d:href><d:status>invalid</d:status></d:response>",
            healthy(one) + "<d:response><d:href>$two</d:href><d:status>HTTP/1.1 404 Gone</d:status><d:status>HTTP/1.1 410 Gone</d:status></d:response>",
        ) + listOf(401, 403, 429, 500, 507).map { healthy(one) + status(two, it) }
        invalidReports.forEach { body ->
            val notifications = mutableListOf<Int>()
            val methods = mutableListOf<String>()
            assertFails {
                loadGroupwareContactsInBatches(book, onConcurrentDeletion = { notifications += it }) { request ->
                    methods += request.method
                    if (request.method == "PROPFIND") listing(listOf(one, two)) else xml(body)
                }
            }
            assertTrue(notifications.isEmpty())
            assertEquals(listOf("PROPFIND", "REPORT"), methods)
        }
    }

    @Test
    fun missingPropertiesDoNotProveResourceDeletion() = runBlocking {
        val href = "${book}one"
        val missingOptionalProperty = "<d:propstat><d:prop><d:displayname /></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>"
        var deleted = 0
        val contacts = loadGroupwareContactsInBatches(book, onConcurrentDeletion = { deleted += it }) { request ->
            if (request.method == "PROPFIND") listing(listOf(href))
            else xml(healthy(href).replace("<d:propstat>", "$missingOptionalProperty<d:propstat>"))
        }
        assertEquals(listOf(href), contacts.map(GroupwareContact::href))
        assertEquals(0, deleted)

        listOf(
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><card:address-data /></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
            healthy(href).replace("<d:propstat>", "<d:status>HTTP/1.1 404 Not Found</d:status><d:propstat>"),
        ).forEach { body ->
            assertFails {
                loadGroupwareContactsInBatches(book, onConcurrentDeletion = { deleted += it }) { request ->
                    if (request.method == "PROPFIND") listing(listOf(href)) else xml(body)
                }
            }
            assertEquals(0, deleted)
        }
    }

    private fun listing(hrefs: List<String>) = xml(hrefs.joinToString("") { href ->
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>&quot;old&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    })

    private fun status(href: String, code: Int) =
        "<d:response><d:href>$href</d:href><d:status>HTTP/1.1 $code Resource status</d:status></d:response>"

    private fun healthy(href: String): String {
        val uid = href.substringAfterLast('/')
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\nFN:Contact $uid\r\nEND:VCARD\r\n"
        return "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>&quot;fresh-$uid&quot;</d:getetag><card:address-data>$card</card:address-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }

    private fun xml(members: String) = NextcloudApiResponse(207,
        """<d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">$members</d:multistatus>""".encodeToByteArray(),
        "application/xml", null)
}
