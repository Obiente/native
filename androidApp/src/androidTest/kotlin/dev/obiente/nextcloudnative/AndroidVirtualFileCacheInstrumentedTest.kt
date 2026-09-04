package dev.obiente.nextcloudnative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.VirtualFileCachePolicy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AndroidVirtualFileCacheInstrumentedTest {
    private lateinit var context: Context
    private val session = NextcloudSession(
        serverUrl = "https://cloud.invalid",
        loginName = "virtual-cache-fixture",
        appPassword = "x",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "virtual-files-v1").deleteRecursively()
        File(context.filesDir, "documents-recovery").deleteRecursively()
        context.getSharedPreferences("virtual-file-cache-v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "virtual-files-v1").deleteRecursively()
        File(context.filesDir, "documents-recovery").deleteRecursively()
        context.getSharedPreferences("virtual-file-cache-v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun hydrationPublishesExactGenerationAndAnActiveLeaseBlocksEviction() {
        val cache = AndroidVirtualFileCache(context)
        val bytes = "synthetic virtual content".encodeToByteArray()
        val staging = cache.createHydrationStagingFile().apply { writeBytes(bytes) }
        val file = NextcloudFile(
            path = "Studio/Client selects/portrait.raf",
            name = "portrait.raf",
            isDirectory = false,
            mimeType = "image/x-fuji-raf",
            size = bytes.size.toLong(),
            lastModified = null,
            fileId = 42L,
            hasPreview = true,
            etag = "\"raf-v1\"",
        )

        cache.publishHydration(session, file, staging, nowEpochMillis = 10L)
        val lease = requireNotNull(
            cache.acquire(session, file.path, expectedRemoteEtag = "\"raf-v1\"", nowEpochMillis = 20L),
        )
        assertArrayEquals(bytes, lease.content.readBytes())
        assertNull(cache.acquire(session, file.path, expectedRemoteEtag = "\"raf-v2\""))

        val secondCache = AndroidVirtualFileCache(context)
        secondCache.savePolicy(
            VirtualFileCachePolicy(
                automaticCleanup = true,
                maximumCacheBytes = 1L,
                minimumFreeSpaceBytes = 0L,
                unusedFileAgeMillis = null,
            ),
        )
        val secondLease = requireNotNull(secondCache.acquire(session, file.path, expectedRemoteEtag = "\"raf-v1\""))
        secondLease.release()

        lease.release()
        cache.freeUp(session, bytes.size.toLong())
        assertNull(cache.acquire(session, file.path, expectedRemoteEtag = "\"raf-v1\""))
    }

    @Test
    fun cacheStartupReclaimsInterruptedOwnedHydrationStages() {
        val stagingDirectory = File(context.cacheDir, "virtual-files-v1/staging").apply { mkdirs() }
        val interrupted = File(stagingDirectory, "hydrate-interrupted.part").apply {
            writeText("partial bytes")
        }
        val unrelated = File(stagingDirectory, "user-file.part").apply { writeText("preserve") }

        AndroidVirtualFileCache(context)

        assertFalse(interrupted.exists())
        org.junit.Assert.assertTrue(unrelated.exists())
    }

    @Test
    fun durableDocumentWritebackManifestIsScopedAndRequiresItsStage() {
        val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
        val stage = File(recovery, "writeback-fixture.stage").apply { writeText("local edit") }
        File(recovery, stage.name + ".json").writeText(
            JSONObject()
                .put("version", 1)
                .put("account", NextcloudDocumentIds.accountKey(session))
                .put("path", "Notes/draft.md")
                .put("etag", "\"v1\"")
                .put("displayName", "draft.md")
                .put("stage", stage.name)
                .put("startedAt", 10L)
                .put("ready", true)
                .toString(),
        )

        assertEquals(1, androidDocumentPendingWritebackCount(context, session))
        assertEquals(
            "Notes/draft.md",
            androidDocumentPendingWriteback(context, session, "Notes/draft.md")?.remotePath,
        )
        stage.delete()
        assertEquals(0, androidDocumentPendingWritebackCount(context, session))
    }

    @Test
    fun processRestoredWritebackBlocksDestructiveMutationUntilRecovery() {
        val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
        val stage = File(recovery, "writeback-restored.stage").apply { writeText("local edit") }
        File(recovery, stage.name + ".json").writeText(
            JSONObject()
                .put("version", 1)
                .put("account", NextcloudDocumentIds.accountKey(session))
                .put("path", "Projects/Active/notes.txt")
                .put("etag", "\"v1\"")
                .put("displayName", "notes.txt")
                .put("stage", stage.name)
                .put("startedAt", 10L)
                .put("ready", true)
                .toString(),
        )

        var blockedMutationRan = false
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            withNoBlockingAndroidDocumentWriteback(context, session, "Projects/Active") {
                blockedMutationRan = true
            }
        }
        org.junit.Assert.assertFalse(blockedMutationRan)
        var unrelatedMutationRan = false
        withNoBlockingAndroidDocumentWriteback(context, session, "Projects/Archive") {
            unrelatedMutationRan = true
        }
        org.junit.Assert.assertTrue(unrelatedMutationRan)
    }

    @Test
    fun processRestoredWritebackBlocksNativeTextSaveUntilRecovery() {
        MockWebServer().use { server ->
            server.start()
            val saveSession = session.copy(serverUrl = server.url("/").toString())
            val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
            val stage = File(recovery, "writeback-text-save.stage").apply { writeText("local edit") }
            File(recovery, stage.name + ".json").writeText(
                JSONObject()
                    .put("version", 1)
                    .put("account", NextcloudDocumentIds.accountKey(saveSession))
                    .put("path", "Notes/draft.md")
                    .put("etag", "\"v1\"")
                    .put("displayName", "draft.md")
                    .put("stage", stage.name)
                    .put("startedAt", 10L)
                    .put("ready", true)
                    .toString(),
            )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    AndroidNextcloudServices(context).saveTextFile(
                        session = saveSession,
                        userId = "virtual-cache-fixture",
                        path = "Notes/draft.md",
                        text = "native editor update",
                        expectedEtag = "\"v1\"",
                    )
                }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun processRestoredWritebackBlocksFileSyncRemoteDeletion() {
        MockWebServer().use { server ->
            server.start()
            val syncSession = session.copy(serverUrl = server.url("/").toString())
            val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
            val stage = File(recovery, "writeback-sync-guard.stage").apply { writeText("local edit") }
            File(recovery, stage.name + ".json").writeText(
                JSONObject()
                    .put("version", 1)
                    .put("account", NextcloudDocumentIds.accountKey(syncSession))
                    .put("path", "Projects/Active/notes.txt")
                    .put("etag", "\"v1\"")
                    .put("displayName", "notes.txt")
                    .put("stage", stage.name)
                    .put("startedAt", 10L)
                    .put("ready", true)
                    .toString(),
            )
            server.enqueue(
                MockResponse.Builder().code(207).body(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response>
                      <d:href>/remote.php/dav/files/virtual-cache-fixture/Projects/Active/notes.txt</d:href>
                      <d:propstat><d:prop><d:displayname>notes.txt</d:displayname>
                        <d:getetag>"v1"</d:getetag><d:getcontentlength>10</d:getcontentlength>
                        <d:resourcetype/>
                      </d:prop></d:propstat>
                    </d:response></d:multistatus>
                    """.trimIndent(),
                ).build(),
            )
            val remote = AndroidFileSyncRemoteTree(
                session = syncSession,
                userId = "virtual-cache-fixture",
                remoteRootPath = "",
                webDav = NextcloudDocumentWebDav(),
                documentWritebackContext = context,
            )

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                remote.delete("Projects/Active/notes.txt", "\"v1\"")
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun mutationReservationReleasesTheGlobalLockAndBlocksOnlyOverlappingEdits() {
        val operationStarted = CountDownLatch(1)
        val finishOperation = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mutation = executor.submit {
                withNoBlockingAndroidDocumentWriteback(context, session, "Projects/Active") {
                    operationStarted.countDown()
                    check(finishOperation.await(10, TimeUnit.SECONDS))
                }
            }
            org.junit.Assert.assertTrue(operationStarted.await(10, TimeUnit.SECONDS))

            reserveAndroidDocumentWritebackPath(session, "Projects/Archive/notes.txt")
            releaseAndroidDocumentWritebackPath(session, "Projects/Archive/notes.txt")
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                reserveAndroidDocumentWritebackPath(session, "Projects/Active/notes.txt")
            }

            finishOperation.countDown()
            mutation.get(10, TimeUnit.SECONDS)
            reserveAndroidDocumentWritebackPath(session, "Projects/Active/notes.txt")
            releaseAndroidDocumentWritebackPath(session, "Projects/Active/notes.txt")

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                withNoBlockingAndroidDocumentWriteback(context, session, "Projects/Failed") {
                    throw IllegalArgumentException("synthetic mutation failure")
                }
            }
            reserveAndroidDocumentWritebackPath(session, "Projects/Failed/notes.txt")
            releaseAndroidDocumentWritebackPath(session, "Projects/Failed/notes.txt")
        } finally {
            finishOperation.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun providerStartupDiscardsIncompleteWritebacksAndKeepsReadyRecovery() {
        val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
        fun writeTransaction(name: String, ready: Boolean) {
            val stage = File(recovery, "writeback-$name.stage").apply { writeText("local edit") }
            File(recovery, stage.name + ".json").writeText(
                JSONObject()
                    .put("version", 1)
                    .put("account", NextcloudDocumentIds.accountKey(session))
                    .put("path", "Notes/$name.md")
                    .put("etag", "\"v1\"")
                    .put("displayName", "$name.md")
                    .put("stage", stage.name)
                    .put("startedAt", 10L)
                    .put("ready", ready)
                    .toString(),
            )
        }
        writeTransaction("unfinished", ready = false)
        writeTransaction("recoverable", ready = true)
        File(recovery, "writeback-orphan.stage").writeText("partial")
        File(recovery, "manifest-orphan.tmp").writeText("partial")

        assertEquals(4, cleanupIncompleteAndroidDocumentWritebacks(context))
        assertEquals(1, androidDocumentPendingWritebackCount(context, session))
        assertEquals(
            "Notes/recoverable.md",
            androidDocumentPendingWritebacks(context, session).single().remotePath,
        )
    }

    @Test
    fun activeDocumentWritebackIsHiddenUntilItsDescriptorReleasesIt() {
        val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
        val stage = File(recovery, "writeback-active.stage").apply { writeText("open edit") }
        val manifest = File(recovery, stage.name + ".json").apply {
            writeText(
                JSONObject()
                    .put("version", 1)
                    .put("account", NextcloudDocumentIds.accountKey(session))
                    .put("path", "Notes/open.md")
                    .put("etag", "\"v1\"")
                    .put("displayName", "open.md")
                    .put("stage", stage.name)
                    .put("startedAt", 10L)
                    .put("ready", false)
                    .toString(),
            )
        }
        val active = AndroidDocumentPendingWriteback(
            stage,
            manifest,
            NextcloudDocumentIds.accountKey(session),
            "Notes/open.md",
            "\"v1\"",
        )

        active.markReadyAndActive()
        assertEquals(0, androidDocumentPendingWritebackCount(context, session))
        active.releaseActive()
        assertEquals(1, androidDocumentPendingWritebackCount(context, session))
    }

    @Test
    fun ambiguousWritebackCanBePersistedAsAnExplicitConflict() {
        val recovery = File(context.filesDir, "documents-recovery").apply { mkdirs() }
        val stage = File(recovery, "writeback-conflict.stage").apply { writeText("local edit") }
        val manifest = File(recovery, stage.name + ".json").apply {
            writeText(
                JSONObject()
                    .put("version", 1)
                    .put("account", NextcloudDocumentIds.accountKey(session))
                    .put("path", "Notes/conflict.md")
                    .put("etag", "\"v1\"")
                    .put("displayName", "conflict.md")
                    .put("stage", stage.name)
                    .put("startedAt", 10L)
                    .put("ready", true)
                    .toString(),
            )
        }
        val pending = AndroidDocumentPendingWriteback(
            stage,
            manifest,
            NextcloudDocumentIds.accountKey(session),
            "Notes/conflict.md",
            "\"v1\"",
        )

        pending.markConflict("\"v2\"")

        assertEquals(true, androidDocumentPendingWritebacks(context, session).single().conflict)
        assertEquals("local edit", stage.readText())
    }

    @Test
    fun corruptedSameLengthBlobIsRejectedAndRemoved() {
        val cache = AndroidVirtualFileCache(context)
        val bytes = "first payload".encodeToByteArray()
        val file = NextcloudFile(
            path = "Notes/digest.txt",
            name = "digest.txt",
            isDirectory = false,
            mimeType = "text/plain",
            size = bytes.size.toLong(),
            lastModified = null,
            fileId = 7L,
            hasPreview = false,
            etag = "\"digest-v1\"",
        )
        cache.publishHydration(
            session,
            file,
            cache.createHydrationStagingFile().apply { writeBytes(bytes) },
        )
        val lease = requireNotNull(cache.acquire(session, file.path, file.etag))
        lease.content.writeBytes("other payload".encodeToByteArray())
        lease.release()

        assertNull(cache.acquire(session, file.path, file.etag))
    }

    @Test
    fun writebackAdmissionPreservesAFreeSpaceReserve() {
        requireAndroidDocumentWritebackCapacity(
            remoteSize = 64L * 1024L * 1024L,
            availableBytes = 1024L * 1024L * 1024L,
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            requireAndroidDocumentWritebackCapacity(
                remoteSize = 700L * 1024L * 1024L,
                availableBytes = 1024L * 1024L * 1024L,
            )
        }
    }
}
