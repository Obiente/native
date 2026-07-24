package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.SystemTagAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSystemTagsDavTest {
    @Test
    fun parsesNamespacedSystemTagPropertiesWithEffectivePermissions() {
        val tags = parseAndroidSystemTagsDavResponse(SYSTEM_TAGS_XML.encodeToByteArray())

        assertEquals(listOf(3L, 9L), tags.map { it.id })
        val restricted = tags.first()
        assertEquals("Retention", restricted.name)
        assertEquals(SystemTagAccess.Restricted, restricted.access)
        assertFalse(restricted.canAssign)
        val travel = tags.last()
        assertEquals("Travel", travel.name)
        assertEquals("0082c9", travel.color)
        assertEquals("travel-etag", travel.etag)
        assertTrue(travel.canAssign)
    }

    private companion object {
        val SYSTEM_TAGS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/systemtags/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/systemtags/9</d:href>
                <d:propstat><d:prop>
                  <d:getetag>"travel-etag"</d:getetag><oc:id>9</oc:id><oc:display-name>Travel</oc:display-name>
                  <oc:user-visible>true</oc:user-visible><oc:user-assignable>1</oc:user-assignable>
                  <oc:can-assign>true</oc:can-assign><nc:color>0082c9</nc:color>
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/systemtags/3</d:href>
                <d:propstat><d:prop>
                  <oc:id>3</oc:id><oc:display-name>Retention</oc:display-name>
                  <oc:user-visible>1</oc:user-visible><oc:user-assignable>false</oc:user-assignable>
                  <oc:can-assign>0</oc:can-assign><nc:color />
                </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
