package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.xml.sax.SAXException

class JvmNextcloudChunkCollectionTest {
    @Test
    fun `DAV listing returns chunk numbers and exact server sizes`() {
        val bytes = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/uploads/alice/upload-id/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response><d:href>/remote.php/dav/uploads/alice/upload-id/00001</d:href>
                <d:propstat><d:prop><d:getcontentlength>10485760</d:getcontentlength></d:prop></d:propstat>
              </d:response>
              <d:response><d:href>/remote.php/dav/uploads/alice/upload-id/00002</d:href>
                <d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent().encodeToByteArray()

        assertEquals(mapOf(1 to 10L * 1024L * 1024L, 2 to 42L), parseJvmNextcloudChunkCollection(bytes))
    }

    @Test
    fun `DAV listing decodes percent encoded chunk names`() {
        val bytes = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/uploads/alice/upload-id/000%30%31</d:href>
                <d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent().encodeToByteArray()

        assertEquals(mapOf(1 to 42L), parseJvmNextcloudChunkCollection(bytes))
    }

    @Test
    fun `DAV listing rejects entity declarations before parsing`() {
        assertFailsWith<SAXException> {
            parseJvmNextcloudChunkCollection(
                "<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><d/>".encodeToByteArray(),
            )
        }
    }

    @Test
    fun `valid protocol-sized listing is not rejected by an aggregate byte budget`() {
        val xml = buildString {
            append("<d:multistatus xmlns:d=\"DAV:\">")
            repeat(MAX_NEXTCLOUD_UPLOAD_CHUNKS) { index ->
                append("<d:response><d:href>/remote.php/dav/uploads/alice/")
                append("verbose-segment-".repeat(24))
                append('/')
                append((index + 1).toString().padStart(5, '0'))
                append("</d:href><d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength>")
                append("</d:prop></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
        assertEquals(MAX_NEXTCLOUD_UPLOAD_CHUNKS, parseJvmNextcloudChunkCollection(xml.encodeToByteArray()).size)
    }

    @Test
    fun `streamed listing still has a total metadata resource bound`() {
        val xml = "<d:multistatus xmlns:d=\"DAV:\"><!--${"x".repeat(256)}--></d:multistatus>"
        assertFailsWith<IllegalArgumentException> {
            xml.byteInputStream().readNextcloudChunkCollection(maximumResponseBytes = 128L)
        }
    }
}
