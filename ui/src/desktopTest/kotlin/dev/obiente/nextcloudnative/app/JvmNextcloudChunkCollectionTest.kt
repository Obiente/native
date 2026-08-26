package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `DAV listing rejects entity declarations before parsing`() {
        assertFailsWith<IllegalArgumentException> {
            parseJvmNextcloudChunkCollection(
                "<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><d/>".encodeToByteArray(),
            )
        }
    }
}
