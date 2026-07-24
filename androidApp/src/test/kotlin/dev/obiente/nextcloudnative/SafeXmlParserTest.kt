package dev.obiente.nextcloudnative

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeXmlParserTest {
    @Test
    fun parsesNamespaceAwareWebDavXml() {
        val document = SafeXmlParser.parse(
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:displayname>Photos &amp; videos</d:displayname></d:response>
                </d:multistatus>
            """.trimIndent().toByteArray(),
        )

        val names = document.getElementsByTagNameNS("DAV:", "displayname")
        assertEquals("Photos & videos", names.item(0).textContent)
    }

    @Test
    fun rejectsDocumentTypeBeforeParsing() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE data [<!ENTITY local "unsafe">]>
            <data>&local;</data>
        """.trimIndent().toByteArray()

        val error = assertFailsWith<IllegalArgumentException> { SafeXmlParser.parse(xml) }
        assertTrue(error.message.orEmpty().contains("prohibited document type"))
    }

    @Test
    fun detectsDocumentTypeInUtf16Xml() {
        val xml = "<!DOCTYPE data><data/>".toByteArray(StandardCharsets.UTF_16LE)

        assertTrue(SafeXmlParser.containsDocumentType(xml))
    }

    @Test
    fun allowsOrdinaryXmlDeclarations() {
        val xml = "<?xml version=\"1.0\"?><data/>".toByteArray()

        assertFalse(SafeXmlParser.containsDocumentType(xml))
    }
}
