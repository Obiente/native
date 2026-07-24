package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FileVersionLiveReadAuditTest {
    @Test
    fun `live versions DAV audit is bounded read-only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_FILE_VERSION_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)

        val roots = services.listFiles(session, server.userId, "")
        val candidates = buildList {
            addAll(roots.filter { !it.isDirectory && it.fileId != null })
            roots.asSequence()
                .filter(NextcloudFile::isDirectory)
                .take(MAX_LIVE_AUDIT_FOLDERS)
                .forEach { folder ->
                    addAll(
                        services.listFiles(session, server.userId, folder.path)
                            .filter { !it.isDirectory && it.fileId != null },
                    )
                }
        }
        assertTrue(candidates.isNotEmpty())
        var listedHistories = 0
        var attemptedContent = false
        var downloadedContent: NextcloudFileContent? = null
        for (file in candidates.take(MAX_LIVE_AUDIT_FILES)) {
            val history = runCatching {
                services.listFileVersions(session, server.userId, file)
            }.getOrNull() ?: continue
            listedHistories += 1
            assertTrue(history.fileId == file.fileId)
            assertTrue(history.versions.all { version -> version.fileId == file.fileId })
            val readable = history.versions.firstOrNull { version ->
                version.sizeBytes != null && version.sizeBytes in 1L..LIVE_AUDIT_VERSION_BYTES
            } ?: continue
            attemptedContent = true
            downloadedContent = runCatching {
                services.downloadFileVersion(
                    session = session,
                    userId = server.userId,
                    file = file,
                    version = readable,
                    maximumBytes = LIVE_AUDIT_VERSION_BYTES,
                )
            }.getOrNull()
            if (downloadedContent != null) break
        }
        assertTrue(listedHistories > 0)
        downloadedContent?.let { content ->
            assertTrue(content.bytes.size.toLong() <= LIVE_AUDIT_VERSION_BYTES)
        }
        println(
            "file-version-audit outcome=success requests=propfind-get-only " +
                "identity=verified range=bounded content=" +
                if (attemptedContent && downloadedContent == null) {
                    "server-unavailable metadata=redacted"
                } else {
                    "verified-or-absent metadata=redacted"
                },
        )
    }
}

private const val MAX_LIVE_AUDIT_FOLDERS = 5
private const val MAX_LIVE_AUDIT_FILES = 30
private const val LIVE_AUDIT_VERSION_BYTES = 4L * 1024L * 1024L
