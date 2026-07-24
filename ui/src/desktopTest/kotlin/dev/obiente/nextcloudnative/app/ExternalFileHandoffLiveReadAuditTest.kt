package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalFileHandoffLiveReadAuditTest {
    @Test
    fun `live detached handoff generation is ETag verified without launch or mutation`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FILE_HANDOFF_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)
        val roots = services.listFiles(session, server.userId, "")
        var candidate = roots.asSequence()
            .filter(::isBoundedLiveHandoffCandidate)
            .firstOrNull()
        if (candidate == null) {
            for (folder in roots.filter(NextcloudFile::isDirectory).take(MAX_LIVE_HANDOFF_FOLDERS)) {
                candidate = services.listFiles(session, server.userId, folder.path)
                    .firstOrNull(::isBoundedLiveHandoffCandidate)
                if (candidate != null) break
            }
        }
        candidate = candidate ?: error("The live account has no small versioned file for the handoff audit.")
        val beforeEtag = assertNotNull(candidate.etag)
        val content = services.downloadFile(
            session,
            server.userId,
            candidate.path,
            LIVE_HANDOFF_MAXIMUM_BYTES,
        )

        assertNull(validateDownloadedExternalFile(candidate, content, LIVE_HANDOFF_MAXIMUM_BYTES))
        assertEquals(beforeEtag, content.etag)
        val parent = candidate.path.substringBeforeLast('/', missingDelimiterValue = "")
        val after = assertNotNull(
            services.listFiles(session, server.userId, parent)
                .singleOrNull { file -> file.fileId == candidate.fileId },
        )
        assertEquals(beforeEtag, after.etag)
        assertTrue(content.bytes.isNotEmpty())
        println(
            "external-handoff-audit outcome=success methods=propfind-get-only " +
                "generation=etag-verified staging=not-run launch=not-run mutations=none content=redacted",
        )
    }
}

private fun isBoundedLiveHandoffCandidate(file: NextcloudFile): Boolean =
    !file.isDirectory &&
        file.originalAccessAllowed &&
        !file.etag.isNullOrBlank() &&
        file.fileId != null &&
        file.size?.let { it in 1L..LIVE_HANDOFF_MAXIMUM_BYTES } == true

private const val LIVE_HANDOFF_MAXIMUM_BYTES = 1L * 1024L * 1024L
private const val MAX_LIVE_HANDOFF_FOLDERS = 8
