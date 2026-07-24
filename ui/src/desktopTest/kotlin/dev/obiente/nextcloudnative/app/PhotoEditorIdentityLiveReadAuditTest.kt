package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in authenticated regression for a Memories virtual item.
 *
 * The audit performs one Memories GET followed by one DAV PROPFIND folder listing. It never calls
 * an edit, sidecar write, export, tag, or other mutation endpoint.
 */
class PhotoEditorIdentityLiveReadAuditTest {
    @Test
    fun `live Memories item resolves to its canonical DAV file without mutation`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_PHOTO_IDENTITY_AUDIT") != "1") return@runBlocking
        val fileId = assertNotNull(System.getenv("NEXTCLOUD_PHOTO_IDENTITY_FILE_ID")?.toLongOrNull())
        val expectedName = assertNotNull(System.getenv("NEXTCLOUD_PHOTO_IDENTITY_EXPECTED_NAME"))
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        var observedRequest: NextcloudApiRequest? = null
        val virtual = NextcloudFile(
            path = "memories/people/live-audit/$fileId",
            name = expectedName,
            isDirectory = false,
            mimeType = "image/jpeg",
            size = null,
            lastModified = null,
            fileId = fileId,
            hasPreview = true,
        )

        val source = assertNotNull(
            resolvePhotoEditDavSource(virtual) { requestedId ->
                val request = memoriesPhotoFileIdentityRequest(requestedId)
                assertEquals(NextcloudApiMethod.GET, request.method)
                assertEquals(null, request.body)
                observedRequest = request
                parseMemoriesPhotoFileIdentity(services.executeNextcloudApi(session, request), requestedId)
            },
        )
        val parent = source.path.substringBeforeLast('/', missingDelimiterValue = "")
        val canonical = services.listFiles(session, server.userId, parent)
            .singleOrNull { it.fileId == source.fileId }

        assertEquals(expectedName, source.name)
        assertEquals(source.path, canonical?.path)
        assertTrue(source.path != virtual.path && !source.path.startsWith("memories/"))
        assertTrue(observedRequest?.relativePath?.endsWith("/$fileId") == true)
        println(
            "photo-identity-audit outcome=success methods=get-propfind-only " +
                "identity=verified content=redacted mutations=none",
        )
    }
}
