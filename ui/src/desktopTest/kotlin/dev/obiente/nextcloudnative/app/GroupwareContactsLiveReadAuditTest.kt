package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in saved-session audit. Every request is PROPFIND or REPORT and no server data is mutated.
 */
class GroupwareContactsLiveReadAuditTest {
    @Test
    fun `saved session discovers and parses real carddav contacts`() = runBlocking {
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())

        val principalResponse = services.executeGroupwareDav(
            session,
            groupwareDavPrincipalDiscoveryRequest(),
        )
        val principal = parseGroupwarePrincipalHref(principalResponse)
        val homesResponse = services.executeGroupwareDav(
            session,
            groupwareDavHomeDiscoveryRequest(principal),
        )
        val homes = parseGroupwareDavHomes(homesResponse)
        val addressBookHome = assertNotNull(homes.addressBookHref)
        val collectionResponse = services.executeGroupwareDav(
            session,
            groupwareDavCollectionDiscoveryRequest(addressBookHome),
        )
        val addressBooks = parseGroupwareAddressBooks(collectionResponse)

        println(
            "CardDAV audit principal=${principalResponse.status} homes=${homesResponse.status} " +
                "collections=${collectionResponse.status} addressBooks=${addressBooks.size}",
        )
        assertTrue(addressBooks.isNotEmpty(), "The real CardDAV home advertised no address books.")

        var parsedTotal = 0
        addressBooks.forEachIndexed { index, addressBook ->
            val response = services.executeGroupwareDav(
                session,
                groupwareDavCollectionQueryRequest(
                    collectionHref = addressBook.href,
                    kind = GroupwareDavKind.Contact,
                    maxResults = 250,
                ),
            )
            val text = response.body.decodeToString()
            val contacts = parseGroupwareContacts(addressBook.href, response)
            parsedTotal += contacts.size
            println(
                "CardDAV audit book=$index status=${response.status} bytes=${response.body.size} " +
                    "addressData=${text.contains("address-data", ignoreCase = true)} " +
                    "vcard=${text.contains("BEGIN:VCARD", ignoreCase = true)} parsed=${contacts.size}",
            )
            assertTrue(
                !text.contains("BEGIN:VCARD", ignoreCase = true) || contacts.isNotEmpty(),
                "The server returned vCards for address book $index but none were parsed.",
            )
        }
        println("CardDAV audit parsedTotal=$parsedTotal")
        assertTrue(parsedTotal > 0, "The real CardDAV address books returned no parsed contacts.")
    }
}
