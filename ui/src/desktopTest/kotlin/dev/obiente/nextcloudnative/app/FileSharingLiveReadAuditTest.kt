package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Opt-in saved-session audit. It performs only capability, DAV listing and bounded OCS GET
 * requests. It does not decode or print recipient records, tokens or share URLs.
 */
class FileSharingLiveReadAuditTest {
    @Test
    fun `live file sharing contract remains readable and same origin`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FILE_SHARING_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        assertTrue(server.fileSharing.apiEnabled)

        val roots = services.listFiles(session, server.userId, "")
        val candidate = roots.firstOrNull { file ->
            file.permissions == null || 'R' in file.permissions
        } ?: error("The root listing did not contain a shareable audit candidate.")
        val request = ListFileSharesRequest(candidate.path).toNextcloudApiRequest()
        val response = services.executeNextcloudApi(session, request)
        assertTrue(response.status in 200..299)
        assertTrue(response.body.size.toLong() <= request.maximumResponseBytes)
        println(
            "file-sharing-audit outcome=success methods=capabilities-propfind-get-only " +
                "api=true response-metadata-only content=redacted",
        )
    }

    @Test
    fun `live sharee discovery checks only bounded response metadata`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FILE_SHARING_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        assertTrue(server.fileSharing.apiEnabled)
        val searches = listOf(
            FileShareTarget.User to FileShareItemType.File,
            FileShareTarget.Group to FileShareItemType.Folder,
            FileShareTarget.Email to FileShareItemType.File,
            FileShareTarget.Remote to FileShareItemType.Folder,
        )
        searches.forEach { (target, itemType) ->
            val request = SearchFileShareRecipientsRequest(
                query = "zz",
                target = target,
                itemType = itemType,
                limit = 5,
            ).toNextcloudApiRequest()
            assertEquals(NextcloudApiMethod.GET, request.method)
            assertEquals(itemType.wireValue, request.queryParameters["itemType"])
            assertEquals(target.wireValue.toString(), request.queryParameters["shareType"])
            assertFalse(request.queryParameters["search"].isNullOrBlank())
            assertEquals(null, request.body)
            val response = services.executeNextcloudApi(session, request)
            assertTrue(response.status in 200..299)
            assertTrue(response.body.size.toLong() <= request.maximumResponseBytes)
        }

        println(
            "sharee-audit outcome=success methods=get-only response-metadata-only " +
                "content=redacted",
        )
    }
}
