package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AndroidFileVersionDavTest {
    @Test
    fun `parses only successful DAV property sets without decoding href identity`() {
        val records = parseAndroidFileVersionDavRecords(FILE_VERSION_MULTISTATUS.trimIndent().encodeToByteArray())

        assertEquals(2, records.size)
        assertEquals(
            "/remote.php/dav/versions/person%40example.test/versions/42",
            records.first().href,
        )
        assertEquals("128", records.last().contentLength)
        assertEquals("\"historic\"", records.last().etag)
        assertEquals("editor", records.last().author)
        assertEquals("Before review", records.last().label)
    }

    @Test
    fun `rejects external entities in file version DAV`() {
        assertFails {
            parseAndroidFileVersionDavRecords(
                """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY leak SYSTEM "file:///etc/passwd">]><x>&leak;</x>"""
                    .encodeToByteArray(),
            )
        }
    }
}

private const val FILE_VERSION_MULTISTATUS = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:multistatus xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">
      <d:response>
        <d:href>/remote.php/dav/versions/person%40example.test/versions/42</d:href>
        <d:propstat><d:prop><d:getcontentlength>0</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
      </d:response>
      <d:response>
        <d:href>/remote.php/dav/versions/person%40example.test/versions/42/1730000000</d:href>
        <d:propstat>
          <d:prop><d:getcontentlength>wrong</d:getcontentlength></d:prop>
          <d:status>HTTP/1.1 404 Not Found</d:status>
        </d:propstat>
        <d:propstat>
          <d:prop>
            <d:getcontentlength>128</d:getcontentlength><d:getlastmodified>Wed, 03 Jul 2024 09:46:40 GMT</d:getlastmodified>
            <d:getetag>"historic"</d:getetag><nc:version-author>editor</nc:version-author>
            <nc:version-label>Before review</nc:version-label>
          </d:prop>
          <d:status>HTTP/1.1 200 OK</d:status>
        </d:propstat>
      </d:response>
      <d:response>
        <d:href>/remote.php/dav/versions/person%40example.test/versions/42/1720000000</d:href>
        <d:propstat><d:prop/><d:status>HTTP/1.1 403 Forbidden</d:status></d:propstat>
      </d:response>
    </d:multistatus>
"""
