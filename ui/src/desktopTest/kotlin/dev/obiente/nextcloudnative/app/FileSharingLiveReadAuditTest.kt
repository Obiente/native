package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Opt-in saved-session audit. It performs only capability, DAV listing and OCS GET requests and
 * never prints file names, recipients, tokens or share URLs.
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
        val shares = services.listFileShares(session, candidate.path)
        assertTrue(shares.all { share ->
            share.url == null || safeFileShareUrl(session, share) != null
        })
        println(
            "file-sharing-audit outcome=success methods=capabilities-propfind-get-only " +
                "api=true content=redacted",
        )
    }

    @Test
    fun `live sharee discovery is item aware GET only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FILE_SHARING_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        assertTrue(server.fileSharing.apiEnabled)
        val query = deriveLiveShareeAuditQuery(session, server)
        val searches = listOf(
            FileShareTarget.User to FileShareItemType.File,
            FileShareTarget.Group to FileShareItemType.Folder,
            FileShareTarget.Email to FileShareItemType.File,
            FileShareTarget.Remote to FileShareItemType.Folder,
        )
        val parsed = searches.associate { (target, itemType) ->
            val request = SearchFileShareRecipientsRequest(
                query = query,
                target = target,
                itemType = itemType,
                limit = 5,
            ).toNextcloudApiRequest()
            assertEquals(NextcloudApiMethod.GET, request.method)
            assertEquals(itemType.wireValue, request.queryParameters["itemType"])
            assertEquals(target.wireValue.toString(), request.queryParameters["shareType"])
            assertFalse(request.queryParameters["search"].isNullOrBlank())
            assertEquals(null, request.body)
            target to parseFileShareRecipientsResponse(
                services.executeNextcloudApi(session, request),
                target,
            )
        }

        assertTrue(parsed.getValue(FileShareTarget.User).all { it.target == FileShareTarget.User })
        assertTrue(parsed.getValue(FileShareTarget.Group).all { it.target == FileShareTarget.Group })
        assertTrue(parsed.getValue(FileShareTarget.Email).all { it.target == FileShareTarget.Email })
        assertTrue(parsed.getValue(FileShareTarget.Remote).all { it.target == FileShareTarget.Remote })
        println(
            "sharee-audit outcome=success methods=get-only item-types=file-folder " +
                "content=redacted",
        )
    }

    private fun deriveLiveShareeAuditQuery(
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ): String {
        val candidates = listOf(session.loginName, server.userId)
        return candidates.firstNotNullOfOrNull { value ->
            value.filter(Char::isLetterOrDigit)
                .take(MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH)
                .takeIf { it.length >= MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH }
        } ?: error("The saved account cannot provide a safe sharee audit query.")
    }
}
