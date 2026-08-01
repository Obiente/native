package dev.obiente.nextcloudnative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.VirtualFileCachePolicy
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
