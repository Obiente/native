package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupwareContactRequestXmlTest {
    @Test
    fun `every contact multiget batch is valid namespace aware XML from its first byte`() {
        val addressBook = "/remote.php/dav/addressbooks/users/synthetic/contacts/"
        for (size in listOf(1, 2, 10)) {
            val hrefs = List(size) { "$addressBook${it}&contact.vcf" }
            val request = groupwareDavAddressBookMultiGetRequest(addressBook, hrefs)
            val bytes = requireNotNull(request.body)
            assertTrue(bytes.decodeToString().startsWith("<?xml"))
            val parser = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newDocumentBuilder()
            val document = parser.parse(ByteArrayInputStream(bytes))
            assertEquals("urn:ietf:params:xml:ns:carddav", document.documentElement.namespaceURI)
            assertEquals("addressbook-multiget", document.documentElement.localName)
            val requested = document.getElementsByTagNameNS("DAV:", "href")
            assertEquals(hrefs, List(requested.length) { requested.item(it).textContent })
            assertEquals(1, document.getElementsByTagNameNS("DAV:", "getetag").length)
            assertEquals(1, document.getElementsByTagNameNS("urn:ietf:params:xml:ns:carddav", "address-data").length)
            assertEquals("REPORT", request.method)
            assertEquals(1, request.depth)
            assertEquals(addressBook, request.relativePath)
        }
    }
}
