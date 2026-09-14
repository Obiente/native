package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileListingHttpException
import dev.obiente.nextcloudnative.app.NextcloudFileListingSource
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidAccountFileListingTest {
    @Test
    fun mutationMetadataReusesLeaseForRenameDeleteMoveAndNestedCreate() = withCache { cache ->
        val guard = AndroidAccountOperationGuard()
        val session = session()
        val key = NextcloudDocumentIds.accountKey(session)
        val lookups = listOf("rename", "delete", "move source", "move destination", "nested create")
        lookups.forEach { operation ->
            val lease = acquireAndroidDocumentMutationAccountLease(session, { session }, guard)
            try {
                fun findDocument(path: String): NextcloudFile = runBlocking {
                    withTimeout(1_000) {
                        loadAndroidAccountFileListing(
                            session, { error("The mutation already validated its session") }, cache,
                            NextcloudDocumentIds.parentPath(path), accountLeaseHeld = true, guard = guard,
                        ) {
                            assertFalse(guard.tryWithAccount(key, unavailable = { false }, action = { true }), operation)
                            AndroidDavFileListingResponse(207, listOf(file("", true), file(path, true)))
                        }.files.single()
                    }
                }
                if (operation in listOf("move destination", "nested create")) {
                    requireAndroidDocumentDirectory(NextcloudDocumentReference(key, "Notes/Child"), ::findDocument)
                } else {
                    assertEquals("Notes/Child", findDocument("Notes/Child").path, operation)
                }
                runBlocking {
                    assertFalse(guard.tryWithAccount(key, unavailable = { false }, action = { true }), operation)
                }
            } finally {
                lease.close()
            }
            runBlocking { assertTrue(guard.tryWithAccount(key, unavailable = { false }, action = { true }), operation) }
        }
    }

    @Test
    fun ordinaryListingWaitsForLeaseAndRejectsRemovedSessionBeforeRequest() = withCache { cache ->
        runBlocking {
            val guard = AndroidAccountOperationGuard()
            val session = session()
            var current: NextcloudSession? = session
            val lease = guard.acquireBlocking(NextcloudDocumentIds.accountKey(session))
            var requested = false
            val listing = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    loadAndroidAccountFileListing(session, { current }, cache, "", guard = guard) {
                        requested = true
                        AndroidDavFileListingResponse(207, emptyList())
                    }
                }
            }
            try {
                assertFalse(listing.isCompleted)
                assertFalse(requested)
                current = null
            } finally {
                lease.close()
            }
            val result = withTimeout(1_000) { listing.await() }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertFalse(requested)
        }
    }

    @Test
    fun listingExtractionPreservesNetworkSortingAndOfflineFallbackBoundaries() = withCache { cache ->
        runBlocking {
            val session = session()
            val guard = AndroidAccountOperationGuard()
            val root = file("", true)
            val directory = file("Notes", true)
            val alpha = file("a.txt")
            val zulu = file("Z.txt")
            val fresh = loadAndroidAccountFileListing(session, { session }, cache, "", guard = guard) {
                assertFalse(guard.tryWithAccount(
                    NextcloudDocumentIds.accountKey(session), unavailable = { false }, action = { true },
                ))
                AndroidDavFileListingResponse(207, listOf(root, zulu, alpha, directory))
            }
            assertEquals(NextcloudFileListingSource.Network, fresh.source)
            assertEquals(listOf(directory, alpha, zulu), fresh.files)
            for (status in listOf(500, 503)) {
                val cached = loadAndroidAccountFileListing(session, { session }, cache, "", guard = guard) {
                    AndroidDavFileListingResponse(status, emptyList())
                }
                assertEquals(NextcloudFileListingSource.Cache, cached.source)
                assertEquals(fresh.files, cached.files)
            }
            val offline = loadAndroidAccountFileListing(session, { session }, cache, "", guard = guard) {
                throw IOException("offline")
            }
            assertEquals(fresh.files, offline.files)
            assertEquals(NextcloudFileListingSource.Cache, offline.source)
            for (status in listOf(401, 403, 404)) {
                assertEquals(status, assertFailsWith<NextcloudFileListingHttpException> {
                    loadAndroidAccountFileListing(session, { session }, cache, "", guard = guard) {
                        AndroidDavFileListingResponse(status, emptyList())
                    }
                }.status)
            }
            assertFailsWith<CancellationException> {
                loadAndroidAccountFileListing(session, { session }, cache, "", guard = guard) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun rootCreateSkipsLookupAndNonDirectoryParentIsRejected() {
        val key = NextcloudDocumentIds.accountKey(session())
        requireAndroidDocumentDirectory(NextcloudDocumentReference(key, "")) { error("Root has no parent listing") }
        assertFailsWith<IllegalArgumentException> {
            requireAndroidDocumentDirectory(NextcloudDocumentReference(key, "Notes.txt")) { file(it) }
        }
    }

    private fun session() = NextcloudSession("https://cloud.example.test", "alice", "fixture-password")

    private fun file(path: String, directory: Boolean = false) = NextcloudFile(
        path = path, name = path.substringAfterLast('/'), isDirectory = directory,
        mimeType = null, size = null, lastModified = null, fileId = null, hasPreview = false, etag = "\"etag\"",
    )

    private fun withCache(block: (AndroidFileReadCache) -> Unit) {
        val root = Files.createTempDirectory("ncn-account-listing-test-").toFile()
        try { block(AndroidFileReadCache(root)) } finally { root.deleteRecursively() }
    }
}
