package dev.obiente.nextcloudnative

import java.nio.file.Files
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
            val protected = root.resolve("protected").apply { mkdir() }
            val active = root.resolve("active").apply { mkdir() }
            val now = 2L * 24L * 60L * 60L * 1000L
            expired.setLastModified(1L)
            protected.setLastModified(1L)
            active.setLastModified(now)

            prepareLargeExternalShareCache(
                root,
                requiredBytes = 0L,
                nowMillis = now,
                protectedDirectoryNames = setOf(protected.name),
            )

            assertFalse(expired.exists())
            assertTrue(protected.exists())
            assertTrue(active.exists())

            active.deleteRecursively()
            protected.deleteRecursively()
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
                protectedDirectoryNames = setOf(oldest.name),
            )

            assertTrue(inProgress.exists())
            assertTrue(oldest.exists())
            assertFalse(newer.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed large handoff reservation removes its operation directory`() {
        val root = Files.createTempDirectory("nextcloud-large-handoff-reservation-test-").toFile()
        try {
            assertFailsWith<IOException> {
                createLargeExternalShareOperationDirectory(root.canonicalFile, expectedBytes = 10L) { file, _ ->
                    file.writeBytes(byteArrayOf(1))
                    throw IOException("reservation failed")
                }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `managed staged handoff resolves exact content and is removed by durable clear`() {
        val cacheDirectory = Files.createTempDirectory("nextcloud-managed-handoff-cache-test-").toFile()
        val stateDirectory = Files.createTempDirectory("nextcloud-managed-handoff-state-test-").toFile()
        val root = androidExternalLargeShareCacheRoot(cacheDirectory).apply { mkdir() }
        val store = AndroidExternalFileHandoffStore(stateDirectory.resolve("records.bin"), root)
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        val file = handoffFile(size = 12L)
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, nowEpochMillis = 10L)
            val record = AndroidExternalFileHandoffRegistry.register(
                session,
                "person-id",
                file,
                nowEpochMillis = 10L,
            )
            val operation = root.resolve("operation").apply { mkdir() }
            operation.resolve(LARGE_EXTERNAL_SHARE_RESERVATION_FILE).writeBytes(ByteArray(0))
            val staged = operation.resolve(file.name).apply { writeText("cached bytes") }
            val published = publishLargeExternalHandoffContent(staged, record.documentId)

            assertEquals(published, resolveLargeExternalHandoffContent(cacheDirectory, record))
            assertEquals("cached bytes", published.readText())
            assertFalse(
                requireNotNull(published.parentFile).resolve(LARGE_EXTERNAL_SHARE_RESERVATION_FILE).exists(),
            )

            AndroidExternalFileHandoffRegistry.clear()
            assertFalse(published.exists())
            assertFalse(store.stateFile.exists())
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            cacheDirectory.deleteRecursively()
            stateDirectory.deleteRecursively()
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
    fun `durable clear failure preserves live handoff authority and reports failure`() {
        val root = Files.createTempDirectory("nextcloud-handoff-clear-test-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("records.bin"))
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, nowEpochMillis = 10L)
            val record = AndroidExternalFileHandoffRegistry.register(
                session,
                "person-id",
                handoffFile(size = 4L),
                nowEpochMillis = 10L,
            )
            assertTrue(store.stateFile.delete())
            assertTrue(store.stateFile.mkdir())
            store.stateFile.resolve("blocker").writeText("keep directory non-empty")

            assertFailsWith<AndroidExternalFileHandoffStoreException> {
                AndroidExternalFileHandoffRegistry.clear()
            }
            assertEquals(
                record,
                AndroidExternalFileHandoffRegistry.peek(record.documentId, session, nowEpochMillis = 11L),
            )
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed full registry insertion restores displaced durable handoff`() {
        val root = Files.createTempDirectory("nextcloud-handoff-register-test-").toFile()
        val store = AndroidExternalFileHandoffStore(root.resolve("records.bin"))
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, nowEpochMillis = 10L)
            val existing = List(AndroidExternalFileHandoffRegistry.MAX_RECORDS) { index ->
                AndroidExternalFileHandoffRegistry.register(
                    session,
                    "person-id",
                    handoffFile(size = 4L),
                    nowEpochMillis = 10L + index,
                )
            }
            val displaced = existing.first()
            assertTrue(store.stateFile.delete())
            assertTrue(store.stateFile.mkdir())
            store.stateFile.resolve("blocker").writeText("keep directory non-empty")

            assertFailsWith<AndroidExternalFileHandoffStoreException> {
                AndroidExternalFileHandoffRegistry.register(
                    session,
                    "person-id",
                    handoffFile(size = 4L),
                    nowEpochMillis = 100L,
                )
            }
            assertEquals(
                displaced,
                AndroidExternalFileHandoffRegistry.peek(
                    displaced.documentId,
                    session,
                    nowEpochMillis = 101L,
                ),
            )
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }

    @Test
    fun `request cancellation interrupts transport before the coroutine completes`() = runBlocking {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val cancelling = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val child = CoroutineScope(parent).launch {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cancelling.complete(Unit)
                    release.await()
                }
            }
        }
        started.await()
        var cancellations = 0
        val adapter = CoroutineDocumentRequestCancellation(parent)
        adapter.setOnCancelAction { cancellations += 1 }

        parent.cancel()
        cancelling.await()
        assertFalse(parent.isCompleted)
        assertEquals(1, cancellations)

        release.complete(Unit)
        child.join()
        adapter.close()
    }

    @Test
    fun `active remote handoff lease stops authorizing reads at its deadline`() {
        var now = 99L
        val record = AndroidExternalFileHandoffRecord(
            documentId = "nch1:" + "a".repeat(32),
            accountId = "account",
            userId = "person-id",
            file = NextcloudFile(
                path = "Videos/clip.mp4",
                name = "clip.mp4",
                isDirectory = false,
                mimeType = "video/mp4",
                size = 1L,
                lastModified = null,
                fileId = null,
                hasPreview = false,
                etag = "\"v1\"",
            ),
            createdAtEpochMillis = 0L,
            expiresAtEpochMillis = 100L,
        )
        val lease = AndroidExternalFileHandoffLease(
            record = record,
            onRelease = {},
            nowEpochMillis = { now },
        )
        assertTrue(lease.isValid())

        now = 100L
        assertFalse(lease.isValid())
        var rejected = false
        lease.onRevoked { rejected = true }
        assertTrue(rejected)
    }

    @Test
    fun `seekable handoff probe verifies empty generations and only falls back for confirmed incompatibility`() {
        val empty = handoffFile(size = 0L)
        var emptyVerified = false
        assertTrue(
            runBlocking {
                probeSeekableExternalHandoffGeneration(
                    file = empty,
                    verifyEmptyGeneration = { emptyVerified = true },
                    openRangeSession = { _, _ -> error("Empty files do not open a range session") },
                )
            },
        )
        assertTrue(emptyVerified)

        var closed = false
        assertFalse(
            runBlocking {
                probeSeekableExternalHandoffGeneration(
                    file = handoffFile(size = 4L),
                    verifyEmptyGeneration = {},
                    openRangeSession = { size, _ ->
                        NextcloudFileRangeSession(
                            size = size,
                            readBlock = { _, _ -> throw AndroidFileRangeUnsupportedException("no ranges") },
                            closeBlock = { closed = true },
                        )
                    },
                )
            },
        )
        assertTrue(closed)

        assertFailsWith<IOException> {
            runBlocking {
                probeSeekableExternalHandoffGeneration(
                    file = handoffFile(size = 4L),
                    verifyEmptyGeneration = {},
                    openRangeSession = { size, _ ->
                        NextcloudFileRangeSession(
                            size = size,
                            readBlock = { _, _ -> throw IOException("temporary outage") },
                        )
                    },
                )
            }
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

    private fun handoffFile(size: Long) = NextcloudFile(
        path = "Videos/probe.mp4",
        name = "probe.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = size,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = "\"probe-v1\"",
    )
}
