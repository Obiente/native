package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupwareContactsLoadingTest {
    @Test
    fun `initial listing failure does not silently omit a contact`() {
        runBlocking {
            val addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/"
            val failedHref = "${addressBookHref}failed.vcf"

            assertFailsWith<IllegalStateException> {
                loadGroupwareContactsInBatches(addressBookHref) { request ->
                    assertEquals("PROPFIND", request.method)
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:">
                          <d:response><d:href>$addressBookHref</d:href></d:response>
                          <d:response>
                            <d:href>$failedHref</d:href>
                            <d:status>HTTP/1.1 507 Insufficient Storage</d:status>
                          </d:response>
                        </d:multistatus>
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    @Test
    fun `multiget partial failure does not silently omit a contact`() {
        runBlocking {
            val addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/"
            val hrefs = listOf("${addressBookHref}one.vcf", "${addressBookHref}two.vcf")

            assertFailsWith<IllegalArgumentException> {
                loadGroupwareContactsInBatches(addressBookHref) { request ->
                    if (request.method == "PROPFIND") {
                        listingResponse(addressBookHref, hrefs)
                    } else {
                        xmlResponse(
                            """
                            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                              ${successfulContactResponse(hrefs.first(), "one")}
                              <d:response>
                                <d:href>${hrefs.last()}</d:href>
                                <d:status>HTTP/1.1 507 Insufficient Storage</d:status>
                              </d:response>
                            </d:multistatus>
                            """.trimIndent(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `oversized multiget responses split and fall back to bounded object reads`() = runBlocking {
        val addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/"
        val hrefs = (1..4).map { index -> "$addressBookHref$index.vcf" }
        val reportSizes = mutableListOf<Int>()
        val getHrefs = mutableListOf<String>()

        val contacts = loadGroupwareContactsInBatches(addressBookHref) { request ->
            when (request.method) {
                "PROPFIND" -> listingResponse(addressBookHref, hrefs)
                "REPORT" -> {
                    val requestText = requireNotNull(request.body).decodeToString()
                    reportSizes += hrefs.count(requestText::contains)
                    throw NextcloudResponseTooLargeException(request.maximumResponseBytes, responseStatus = 207)
                }
                "GET" -> {
                    getHrefs += request.relativePath
                    NextcloudApiResponse(
                        status = 200,
                        contentType = "text/vcard",
                        etag = "\"get-etag\"",
                        body = vCard(request.relativePath.substringAfterLast('/')).encodeToByteArray(),
                    )
                }
                else -> error("Unexpected request method ${request.method}.")
            }
        }

        assertEquals(listOf(4, 2, 1, 1, 2, 1, 1), reportSizes)
        assertEquals(hrefs, getHrefs)
        assertEquals(4, contacts.size)
        assertTrue(contacts.all { it.rawVCard.isEmpty() })
        assertTrue(contacts.all { it.etag == "\"get-etag\"" })
    }

    @Test
    fun `oversized HTTP error responses do not split a multiget batch`() {
        runBlocking {
            val addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/"
            val hrefs = listOf("${addressBookHref}one.vcf", "${addressBookHref}two.vcf")
            val methods = mutableListOf<String>()

            assertFailsWith<NextcloudResponseTooLargeException> {
                loadGroupwareContactsInBatches(addressBookHref) { request ->
                    methods += request.method
                    if (request.method == "PROPFIND") {
                        listingResponse(addressBookHref, hrefs)
                    } else {
                        throw NextcloudResponseTooLargeException(
                            maximumBytes = 64L * 1024L,
                            responseStatus = 401,
                        )
                    }
                }
            }

            assertEquals(listOf("PROPFIND", "REPORT"), methods)
        }
    }

    @Test
    fun `retention budget discards raw cards and rejects excess summaries`() {
        val budget = GroupwareContactRetentionBudget(maximumContacts = 2, maximumEstimatedBytes = 16_384L)
        val first = budget.retain(contact("one", rawVCard = "PHOTO:${"A".repeat(8_192)}"))
        val second = budget.retain(contact("two", rawVCard = "PHOTO:${"B".repeat(8_192)}"))

        assertTrue(first.rawVCard.isEmpty())
        assertTrue(second.rawVCard.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            budget.retain(contact("three", rawVCard = "PHOTO:${"C".repeat(8_192)}"))
        }

        val byteBudget = GroupwareContactRetentionBudget(maximumContacts = 10, maximumEstimatedBytes = 512L)
        assertFailsWith<IllegalArgumentException> {
            byteBudget.retain(contact("large-summary", rawVCard = "").copy(notes = "N".repeat(512)))
        }
    }

    @Test
    fun `editing reloads one complete card and retains its known etag`() = runBlocking {
        val addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/"
        val objectHref = "${addressBookHref}photo.vcf"
        val photo = "A".repeat(8_192)

        val contact = loadGroupwareContactForEditing(
            addressBookHref = addressBookHref,
            objectHref = objectHref,
            knownEtag = "\"listing-etag\"",
        ) { request ->
            assertEquals("GET", request.method)
            assertEquals(objectHref, request.relativePath)
            assertTrue(request.maximumResponseBytes <= 4L * 1024L * 1024L)
            NextcloudApiResponse(
                status = 200,
                contentType = "text/vcard",
                etag = null,
                body = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:photo\r\nFN:Photo contact\r\nPHOTO:$photo\r\nEND:VCARD\r\n"
                    .encodeToByteArray(),
            )
        }

        assertEquals("\"listing-etag\"", contact.etag)
        assertTrue(contact.rawVCard.contains("PHOTO:$photo"))
    }

    private fun listingResponse(addressBookHref: String, hrefs: List<String>): NextcloudApiResponse = xmlResponse(
        """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>$addressBookHref</d:href></d:response>
          ${hrefs.joinToString("\n") { href ->
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>&quot;listing-etag&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        }}
        </d:multistatus>
        """.trimIndent(),
    )

    private fun successfulContactResponse(href: String, uid: String): String = """
        <d:response>
          <d:href>$href</d:href>
          <d:propstat>
            <d:prop>
              <d:getetag>&quot;etag-$uid&quot;</d:getetag>
              <card:address-data>${vCard(uid)}</card:address-data>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>
    """.trimIndent()

    private fun xmlResponse(body: String): NextcloudApiResponse = NextcloudApiResponse(
        status = 207,
        contentType = "application/xml",
        etag = null,
        body = body.encodeToByteArray(),
    )

    private fun vCard(uid: String): String =
        "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:$uid\r\nFN:Contact $uid\r\nEND:VCARD\r\n"

    private fun contact(uid: String, rawVCard: String): GroupwareContact = GroupwareContact(
        href = "/remote.php/dav/addressbooks/users/opaque-user/contacts/$uid.vcf",
        etag = "\"etag-$uid\"",
        addressBookHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/",
        uid = uid,
        displayName = "Contact $uid",
        rawVCard = rawVCard,
    )
}
