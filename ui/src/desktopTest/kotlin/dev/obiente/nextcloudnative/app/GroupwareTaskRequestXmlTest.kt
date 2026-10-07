package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupwareTaskRequestXmlTest {
    @Test
    fun `every task multiget batch is valid namespace aware XML from its first byte`() {
        val calendar = "/remote.php/dav/calendars/synthetic/tasks/"
        for (size in listOf(1, 2, 10)) {
            val hrefs = List(size) { "$calendar${it}&task.ics" }
            val request = groupwareDavCalendarMultiGetRequest(calendar, hrefs)
            val bytes = requireNotNull(request.body)
            assertTrue(bytes.decodeToString().startsWith("<?xml"))
            val parser = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newDocumentBuilder()
            val document = parser.parse(ByteArrayInputStream(bytes))
            assertEquals("urn:ietf:params:xml:ns:caldav", document.documentElement.namespaceURI)
            assertEquals("calendar-multiget", document.documentElement.localName)
            val requested = document.getElementsByTagNameNS("DAV:", "href")
            assertEquals(hrefs, List(requested.length) { requested.item(it).textContent })
            assertEquals("REPORT", request.method)
            assertEquals(1, request.depth)
            assertEquals(calendar, request.relativePath)
        }
    }
}
