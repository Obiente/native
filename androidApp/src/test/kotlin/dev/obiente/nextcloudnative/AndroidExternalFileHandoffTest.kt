package dev.obiente.nextcloudnative

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession

class AndroidExternalFileHandoffTest {
    @Test
    fun `share and open intents have distinct least privilege payload plans`() {
        val share = androidExternalFileIntentPlan(ExternalFileHandoffAction.Share)
        val open = androidExternalFileIntentPlan(ExternalFileHandoffAction.OpenWith)

        assertEquals("android.intent.action.SEND", share.action)
        assertEquals("Share file", share.chooserTitle)
        assertTrue(share.attachStream)
        assertEquals("android.intent.action.VIEW", open.action)
        assertEquals("Open file with", open.chooserTitle)
        assertFalse(open.attachStream)
    }

    @Test
    fun `cache pruning removes expired handoffs but preserves recent ones`() {
        val root = Files.createTempDirectory("nextcloud-handoff-test-").toFile()
        try {
            val old = root.resolve("old").apply { mkdir() }
            old.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val recent = root.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(4, 5, 6))
            val now = 2L * 24L * 60L * 60L * 1000L
            old.setLastModified(1L)
            recent.setLastModified(now)

            pruneExternalShareCache(root, requiredBytes = 1L, nowMillis = now)

            assertFalse(old.exists())
            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cache pruning rejects a non-directory root`() {
        val root = Files.createTempFile("nextcloud-handoff-test-", ".tmp").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                pruneExternalShareCache(root, requiredBytes = 1L)
            }
        } finally {
            root.delete()
        }
    }

    @Test
    fun `large staged handoff preserves free space and bounds aggregate cache entries`() {
        assertTrue(androidLargeExternalHandoffFitsCapacity(40L, 140L, reserveBytes = 100L))
        assertFalse(androidLargeExternalHandoffFitsCapacity(41L, 140L, reserveBytes = 100L))
        assertFalse(
            androidLargeExternalHandoffFitsCapacity(
                requiredBytes = Long.MAX_VALUE,
                availableBytes = Long.MAX_VALUE,
                reserveBytes = 1L,
            ),
        )

        val root = Files.createTempDirectory("nextcloud-large-handoff-test-").toFile()
        try {
            val expired = root.resolve("expired").apply { mkdir() }
            val active = root.resolve("active").apply { mkdir() }
            val now = 2L * 24L * 60L * 60L * 1000L
            expired.setLastModified(1L)
            active.setLastModified(now)

            prepareLargeExternalShareCache(root, requiredBytes = 0L, nowMillis = now)

            assertFalse(expired.exists())
            assertTrue(active.exists())

            active.deleteRecursively()
            val inProgress = root.resolve("in-progress").apply {
                mkdir()
                resolve(".reservation").writeBytes(ByteArray(4))
                setLastModified(5L)
            }
            val oldest = root.resolve("oldest").apply {
                mkdir()
                resolve("payload").writeBytes(ByteArray(4))
                setLastModified(10L)
            }
            val newer = root.resolve("newer").apply {
                mkdir()
                resolve("payload").writeBytes(ByteArray(4))
                setLastModified(20L)
            }

            prepareLargeExternalShareCache(
                root = root,
                requiredBytes = 5L,
                nowMillis = 100L,
                maximumAggregateBytes = 13L,
                minimumRetentionMillis = 0L,
            )

            assertTrue(inProgress.exists())
            assertFalse(oldest.exists())
            assertTrue(newer.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `remote handoff records are account scoped bounded and revocable`() {
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test",
            loginName = "person",
            appPassword = "secret",
        )
        val otherSession = session.copy(loginName = "other")
        val file = NextcloudFile(
            path = "Videos/clip.mp4",
            name = "clip.mp4",
            isDirectory = false,
            mimeType = "video/mp4",
            size = 4L * 1024L * 1024L * 1024L,
            lastModified = null,
            fileId = 7L,
            hasPreview = true,
            etag = "\"v1\"",
        )
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            val record = AndroidExternalFileHandoffRegistry.register(session, "person-id", file, nowEpochMillis = 10L)
            assertTrue(AndroidExternalFileHandoffRegistry.isHandoffDocumentId(record.documentId))
            assertEquals(record, AndroidExternalFileHandoffRegistry.peek(record.documentId, session, 11L))
            assertEquals(null, AndroidExternalFileHandoffRegistry.peek(record.documentId, otherSession, 11L))

            val leases = List(AndroidExternalFileHandoffRegistry.MAX_READERS_PER_RECORD) {
                requireNotNull(AndroidExternalFileHandoffRegistry.acquire(record.documentId, session, 11L))
            }
            assertEquals(null, AndroidExternalFileHandoffRegistry.acquire(record.documentId, session, 11L))
            var revoked = false
            leases.first().onRevoked { revoked = true }
            AndroidExternalFileHandoffRegistry.revoke(record.documentId)
            assertTrue(revoked)
            assertTrue(leases.none(AndroidExternalFileHandoffLease::isValid))
            leases.forEach(AndroidExternalFileHandoffLease::release)
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        }
    }

    @Test
    fun `remote handoff records expire without retaining account access`() {
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        val file = NextcloudFile(
            path = "Videos/clip.mp4",
            name = "clip.mp4",
            isDirectory = false,
            mimeType = "video/mp4",
            size = 1L,
            lastModified = null,
            fileId = null,
            hasPreview = false,
            etag = "\"v1\"",
        )
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            val record = AndroidExternalFileHandoffRegistry.register(session, "person-id", file, nowEpochMillis = 10L)
            assertEquals(null, AndroidExternalFileHandoffRegistry.peek(record.documentId, session, Long.MAX_VALUE))
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        }
    }

    @Test
    fun `remote handoff records survive process restoration without persisting credentials`() {
        val root = Files.createTempDirectory("nextcloud-handoff-store-test-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("records.bin"))
        val session = NextcloudSession("https://cloud.example.test", "person", "private-app-password")
        val file = NextcloudFile(
            path = "Videos/restored.mp4",
            name = "restored.mp4",
            isDirectory = false,
            mimeType = "video/mp4",
            size = 8L * 1024L * 1024L * 1024L,
            lastModified = "Fri, 14 Aug 2026 20:00:00 GMT",
            fileId = 42L,
            hasPreview = true,
            etag = "\"restored-v1\"",
        )
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, nowEpochMillis = 10L)
            val registered = AndroidExternalFileHandoffRegistry.register(
                session,
                "person-id",
                file,
                nowEpochMillis = 10L,
            )
            assertTrue(store.stateFile.isFile)
            assertFalse(store.stateFile.readBytes().decodeToString().contains(session.appPassword))

            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            AndroidExternalFileHandoffRegistry.bind(
                AndroidExternalFileHandoffStore(store.stateFile),
                nowEpochMillis = 11L,
            )

            val restored = requireNotNull(
                AndroidExternalFileHandoffRegistry.peek(registered.documentId, session, nowEpochMillis = 11L),
            )
            assertEquals(registered.documentId, restored.documentId)
            assertEquals(file.path, restored.file.path)
            assertEquals(file.size, restored.file.size)
            assertEquals(file.etag, restored.file.etag)
            assertEquals("person-id", restored.userId)
        } finally {
            AndroidExternalFileHandoffRegistry.clear()
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }
}
