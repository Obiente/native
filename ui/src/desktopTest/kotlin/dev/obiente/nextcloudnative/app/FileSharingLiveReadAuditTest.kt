package dev.obiente.nextcloudnative.app

import kotlin.test.Test
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
                "api=true records=${shares.size} content=redacted",
        )
    }
}
