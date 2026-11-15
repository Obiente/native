package dev.obiente.nextcloudnative.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareAddressBookCollectionsTest {
    private val home = "/remote.php/dav/addressbooks/users/alice/"

    @Test
    fun addressBooksAreClassifiedByOwner() {
        val books = parseManagedAddressBooks(
            NextcloudApiResponse(
                207,
                """
                <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:oc="http://owncloud.org/ns"
                    xmlns:nc="http://nextcloud.com/ns">
                <d:response><d:href>$home</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>${home}contacts/</d:href><d:propstat><d:prop>
                  <d:displayname>Contacts</d:displayname><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                  <oc:owner-principal>principals/users/alice</oc:owner-principal>
                  <d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set>
                </d:prop></d:propstat></d:response>
                <d:response><d:href>${home}team_shared_by_bob/</d:href><d:propstat><d:prop>
                  <d:displayname>Team</d:displayname><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                  <oc:owner-principal>principals/users/bob</oc:owner-principal><nc:owner-displayname>Bob</nc:owner-displayname>
                </d:prop></d:propstat></d:response>
                <d:response><d:href>${home}z-server-generated--system/</d:href><d:propstat><d:prop>
                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                  <oc:owner-principal>principals/system/system</oc:owner-principal>
                </d:prop></d:propstat></d:response>
                </d:multistatus>
                """.trimIndent().encodeToByteArray(),
                "application/xml",
                null,
            ),
            "alice",
        )
        assertEquals(listOf(AddressBookOwnership.Own, AddressBookOwnership.SharedWithMe, AddressBookOwnership.System), books.map { it.ownership })
        assertTrue(books[0].writable)
        assertEquals("Bob", books[1].ownerName)
        assertEquals("z-server-generated--system", books[2].displayName)
    }

    @Test
    fun addressBookRequestsTargetOnlyAddressBooksInAHome() {
        val href = newAddressBookHref(home, "Work & Friends!", Random(1))
        assertTrue(href.startsWith("${home}work-friends-"))
        val create = addressBookCreateRequest(href, " Work & Friends ")
        assertEquals("MKCOL", create.method)
        val body = create.body!!.decodeToString()
        assertTrue("<card:addressbook/>" in body)
        assertTrue("<d:displayname>Work &amp; Friends</d:displayname>" in body)
        assertEquals("PROPPATCH", addressBookRenameRequest(href, "New").method)
        assertEquals("DELETE", addressBookDeleteRequest(href).method)
        assertFailsWith<IllegalArgumentException> { addressBookDeleteRequest(home) }
        assertFailsWith<IllegalArgumentException> { addressBookDeleteRequest("${home}z-server-generated--system/") }
        assertFailsWith<IllegalArgumentException> { addressBookDeleteRequest("/remote.php/dav/calendars/alice/personal/") }
        assertFailsWith<IllegalArgumentException> { addressBookRenameRequest(href, " ") }
        assertNull(addressBookNameMessage("Fine"))
    }
}
