package dev.obiente.nextcloudnative

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.JvmStagingSpaceReservations
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
    fun `streamed Android handoffs cannot claim the same cache bytes`() {
        val reservations = JvmStagingSpaceReservations()
        val first = reservations.reserve("android-cache", 1_000L, 600L, reserveBytes = 100L)
        val second = reservations.reserve("android-cache", 1_000L, 300L, reserveBytes = 100L)
        try {
            assertFailsWith<IllegalStateException> {
                reservations.reserve("android-cache", 1_000L, 1L, reserveBytes = 100L)
            }
        } finally {
            first.close()
            second.close()
        }
    }

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
    fun `staged handoff keeps the response mime type for the external chooser`() {
        val generic = handoffFile(size = 12L).copy(mimeType = "application/octet-stream")

        assertEquals("video/mp4", externalHandoffFile(generic, "video/mp4").mimeType)
        assertEquals(generic, externalHandoffFile(generic, stagedMimeType = null))
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
    fun `cache pressure preserves newly handed off files`() {
        val root = Files.createTempDirectory("nextcloud-handoff-test-").toFile()
        try {
            val recent = root.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val now = 10L * 60L * 60L * 1000L
            recent.setLastModified(now)

            pruneExternalShareCache(root, requiredBytes = Long.MAX_VALUE, nowMillis = now)

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
    fun `large handoff admission subtracts concurrent logical reservations`() {
        val root = Files.createTempDirectory("nextcloud-large-handoff-capacity-test-").toFile()
        try {
            root.resolve("first").apply {
                mkdir()
                resolve(LARGE_EXTERNAL_SHARE_RESERVATION_FILE).writeBytes(ByteArray(30))
            }
            root.resolve("completed").apply {
                mkdir()
                resolve("payload").writeBytes(ByteArray(20))
            }

            val available = androidLargeExternalHandoffAvailableBytes(root, availableBytes = 100L)

            assertEquals(70L, available)
            assertTrue(androidLargeExternalHandoffFitsCapacity(10L, available, reserveBytes = 60L))
            assertFalse(androidLargeExternalHandoffFitsCapacity(11L, available, reserveBytes = 60L))
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
            val record = AndroidExternalFileHandoffRegistry.register(session, file, nowEpochMillis = 10L)
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
            val record = AndroidExternalFileHandoffRegistry.register(session, file, nowEpochMillis = 10L)
            assertEquals(null, AndroidExternalFileHandoffRegistry.peek(record.documentId, session, Long.MAX_VALUE))
        } finally {
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        }
    }

    @Test
    fun `durable clear failure revokes readers and retry prevents restart restoration`() {
        val root = Files.createTempDirectory("nextcloud-handoff-clear-test-").toFile()
        val stateFile = root.resolve("records.bin")
        val store = AndroidExternalFileHandoffStore(stateFile, deleteStateFile = { false })
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        var cleanupPending = true
        AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
        try {
            AndroidExternalFileHandoffRegistry.bind(store, nowEpochMillis = 10L)
            val record = AndroidExternalFileHandoffRegistry.register(
                session,
                handoffFile(size = 4L),
                nowEpochMillis = 10L,
            )
            val lease = requireNotNull(AndroidExternalFileHandoffRegistry.acquire(record.documentId, session, 11L))
            var revoked = false
            lease.onRevoked { revoked = true }

            assertFalse(
                retryPendingAndroidExternalHandoffCleanup(
                    pending = cleanupPending,
                    clearHandoffs = AndroidExternalFileHandoffRegistry::clear,
                    clearJournal = { cleanupPending = false },
                    recordFailure = {},
                ),
            )
            assertTrue(revoked)
            assertFalse(lease.isValid())
            assertEquals(null, AndroidExternalFileHandoffRegistry.peek(record.documentId, session, 11L))
            assertTrue(stateFile.isFile)

            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            val restartedStore = AndroidExternalFileHandoffStore(stateFile)
            assertTrue(
                retryPendingAndroidExternalHandoffCleanup(
                    pending = cleanupPending,
                    clearHandoffs = { AndroidExternalFileHandoffRegistry.clearPersisted(restartedStore) },
                    clearJournal = { cleanupPending = false },
                    recordFailure = {},
                ),
            )
            AndroidExternalFileHandoffRegistry.bind(restartedStore, nowEpochMillis = 11L)
            assertFalse(cleanupPending)
            assertEquals(null, AndroidExternalFileHandoffRegistry.peek(record.documentId, session, 11L))
            assertFalse(stateFile.exists())
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
        val session = NextcloudSession(
            "https://cloud.example.test",
            "raw-user-identifier",
            "private-app-password",
        )
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
                file,
                nowEpochMillis = 10L,
            )
            assertTrue(store.stateFile.isFile)
            val durableState = store.stateFile.readBytes().decodeToString()
            assertFalse(durableState.contains(session.appPassword))
            assertFalse(durableState.contains(session.loginName))

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
        } finally {
            AndroidExternalFileHandoffRegistry.clear()
            AndroidExternalFileHandoffRegistry.resetProcessStateForTests()
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy handoff state is migrated without retaining its raw user id`() {
        val root = Files.createTempDirectory("nextcloud-legacy-handoff-store-test-").toFile()
        val stateFile = root.resolve("records.bin")
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        val record = AndroidExternalFileHandoffRecord(
            documentId = "nch1:" + "b".repeat(32),
            accountId = NextcloudDocumentIds.accountKey(session),
            file = handoffFile(size = 12L),
            createdAtEpochMillis = 10L,
            expiresAtEpochMillis = 100L,
        )
        try {
            writeLegacyHandoffState(stateFile, record, legacyUserId = "raw-legacy-user-id")

            assertEquals(listOf(record), AndroidExternalFileHandoffStore(stateFile).load())
            assertFalse(stateFile.readBytes().decodeToString().contains("raw-legacy-user-id"))
            DataInputStream(FileInputStream(stateFile)).use { input ->
                assertEquals(0x4e434848, input.readInt())
                assertEquals(2, input.readInt())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeLegacyHandoffState(
        stateFile: java.io.File,
        record: AndroidExternalFileHandoffRecord,
        legacyUserId: String,
    ) {
        DataOutputStream(FileOutputStream(stateFile)).use { output ->
            output.writeInt(0x4e434848)
            output.writeInt(1)
            output.writeInt(1)
            output.writeLegacyString(record.documentId)
            output.writeLegacyString(record.accountId)
            output.writeLegacyString(legacyUserId)
            output.writeLegacyString(record.file.path)
            output.writeLegacyString(record.file.name)
            output.writeBoolean(record.file.mimeType != null)
            record.file.mimeType?.let { output.writeLegacyString(it) }
            output.writeLong(requireNotNull(record.file.size))
            output.writeBoolean(record.file.lastModified != null)
            record.file.lastModified?.let { output.writeLegacyString(it) }
            output.writeBoolean(record.file.fileId != null)
            record.file.fileId?.let(output::writeLong)
            output.writeBoolean(record.file.hasPreview)
            output.writeLegacyString(requireNotNull(record.file.etag))
            output.writeBoolean(record.file.originalAccessAllowed)
            output.writeBoolean(record.file.davPathAuthoritative)
            output.writeLong(record.createdAtEpochMillis)
            output.writeLong(record.expiresAtEpochMillis)
        }
    }

    private fun DataOutputStream.writeLegacyString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
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
