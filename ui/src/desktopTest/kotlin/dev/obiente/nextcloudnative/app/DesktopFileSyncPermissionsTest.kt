package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class DesktopFileSyncPermissionsTest {
    @Test
    fun `large overwrite staging follows explicit parent create permission`() {
        MockWebServer().use { server ->
            server.enqueue(directoryAccessResponse("RGDNVW"))
            server.enqueue(directoryAccessResponse("RGDNVCKW"))
            server.enqueue(directoryAccessResponse(""))
            server.enqueue(directoryAccessResponse("RGDNVW"))
            server.start()
            val remote = DesktopFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString(), "alice", "secret"),
                "alice",
                "Vault",
            ).resumableUploadRemote()

            assertEquals(false, remote.ownedStageCreationAllowed("Shared/archive.bin"))
            assertEquals(true, remote.ownedStageCreationAllowed("Shared/archive.bin"))
            assertEquals(null, remote.ownedStageCreationAllowed("Shared/archive.bin"))
            val replacement = DesktopFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString(), "alice", "secret"),
                "alice",
                "Vault",
            ).resumableUploadRemote(replacingDirectoryEtag = "directory-etag")
            val failure = assertFailsWith<IllegalStateException> {
                replacement.ownedStageCreationAllowed("Shared/archive.bin")
            }
            assertTrue(failure.message.orEmpty().contains("required to replace a directory"))
            repeat(4) {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("0", request.headers["Depth"])
            }
        }
    }

    private fun directoryAccessResponse(permissions: String) =
        MockResponse.Builder().code(207).body(desktopDavDirectoryAccess(permissions, "Shared")).build()
}

internal fun desktopDavDirectoryAccess(permissions: String, name: String = ""): String {
    val suffix = name.takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()
    val permissionProperty = permissions.takeIf(String::isNotEmpty)
        ?.let { "<oc:permissions>$it</oc:permissions>" }.orEmpty()
    return """
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
          <d:href>/remote.php/dav/files/alice/Vault$suffix/</d:href>
          <d:propstat><d:prop><d:getetag>directory-etag</d:getetag>
            <d:resourcetype><d:collection/></d:resourcetype>$permissionProperty
          </d:prop></d:propstat>
        </d:response></d:multistatus>
    """.trimIndent()
}
