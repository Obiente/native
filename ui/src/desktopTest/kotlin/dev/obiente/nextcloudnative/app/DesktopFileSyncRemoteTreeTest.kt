package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DesktopFileSyncRemoteTreeTest {
    @Test
    fun `dav parser preserves plus signs and reads guarded revisions`() {
        val documents = parseDesktopSyncDav(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote.php/dav/files/alice/Photos/July%20%2B%20August/</d:href>
                <d:propstat><d:prop>
                  <d:displayname>July + August</d:displayname>
                  <d:getetag>"directory-etag"</d:getetag>
                  <d:resourcetype><d:collection/></d:resourcetype>
                </d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote.php/dav/files/alice/Photos/July%20%2B%20August/a.RAF</d:href>
                <d:propstat><d:prop>
                  <d:getetag>"file-etag"</d:getetag>
                  <d:getcontentlength>42</d:getcontentlength>
                  <d:getlastmodified>Sat, 01 Aug 2026 12:34:56 GMT</d:getlastmodified>
                  <d:resourcetype/>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent().encodeToByteArray(),
            userId = "alice",
        )

        assertEquals(
            listOf("Photos/July + August", "Photos/July + August/a.RAF"),
            documents.map { it.entry.relativePath },
        )
        assertEquals(SyncEntryKind.Directory, documents.first().entry.kind)
        assertEquals(42L, documents.last().entry.size)
        assertEquals("\"file-etag\"", documents.last().entry.etag)
        assertEquals(1_785_587_696_000L, documents.last().lastModifiedEpochMillis)
    }

    @Test
    fun `dav parser rejects external entities`() {
        assertFails {
            parseDesktopSyncDav(
                """
                <?xml version="1.0"?>
                <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>
                """.trimIndent().encodeToByteArray(),
                userId = "alice",
            )
        }
    }

    @Test
    fun `dav parser rejects excess documents while streaming`() {
        val responses = (1..3).joinToString("") { index ->
            """
            <d:response>
              <d:href>/remote.php/dav/files/alice/Photos/$index.jpg</d:href>
              <d:propstat><d:prop><d:getetag>etag-$index</d:getetag></d:prop></d:propstat>
            </d:response>
            """.trimIndent()
        }

        assertFails {
            parseDesktopSyncDav(
                "<d:multistatus xmlns:d=\"DAV:\">$responses</d:multistatus>".encodeToByteArray(),
                userId = "alice",
                maximumDocuments = 2,
            )
        }
    }

    @Test
    fun `dav parser rejects an oversized property without coalescing text`() {
        val oversizedHref = "a".repeat(20_000)
        val response = (
            "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$oversizedHref</d:href>" +
                "</d:response></d:multistatus>"
            ).encodeToByteArray()

        assertFails {
            parseDesktopSyncDav(
                response,
                userId = "alice",
            )
        }
    }

    @Test
    fun `dav parser rejects cdata before materializing property text`() {
        assertFails {
            parseDesktopSyncDav(
                (
                    "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href><![CDATA[" +
                        "a".repeat(20_000) +
                        "]]></d:href></d:response></d:multistatus>"
                    ).encodeToByteArray(),
                userId = "alice",
            )
        }
    }

    @Test
    fun `only exact provider owned upload stages are suppressed`() {
        assertEquals(
            true,
            isDesktopOwnedUploadStage("Photos/.nextcloud-native-123e4567-e89b-12d3-a456-426614174000.upload"),
        )
        assertEquals(false, isDesktopOwnedUploadStage("Photos/.nextcloud-native-not-a-uuid.upload"))
        assertEquals(false, isDesktopOwnedUploadStage("Photos/user-upload.upload"))
    }

    @Test
    fun `only exact provider owned replacement backups reveal a recovery destination`() {
        assertEquals(
            "Photos/today.md",
            desktopOwnedBackupDestination(
                "Photos/.today.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000",
            ),
        )
        assertEquals(null, desktopOwnedBackupDestination("Photos/.today.md.nextcloud-native-backup-not-a-uuid"))
        assertEquals(null, desktopOwnedBackupDestination("Photos/user-backup"))
    }

    @Test
    fun `backup recovery is bounded before orphan processing`() {
        val first = "Photos/.one.jpg.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000"
        val second = "Photos/.two.jpg.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174001"

        assertFails {
            desktopOwnedBackupRecoveryPlan(listOf(first, second), maximumRecoveryItems = 1)
        }
        assertEquals(
            listOf(first to "Photos/one.jpg"),
            desktopOwnedBackupRecoveryPlan(listOf(first), maximumRecoveryItems = 1),
        )
        assertEquals(
            emptyList(),
            desktopOwnedBackupRecoveryPlan(listOf(first, "Photos/one.jpg"), maximumRecoveryItems = 1),
        )
    }

    @Test
    fun `completed replacement backup is exposed when its destination also exists`() {
        val backup = "Photos/.today.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000"

        assertEquals(false, shouldSuppressDesktopOwnedBackup(backup, setOf(backup, "Photos/today.md")))
        assertEquals(true, shouldSuppressDesktopOwnedBackup(backup, setOf(backup)))
    }
}
