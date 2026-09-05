package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import dev.obiente.nextcloudnative.app.localUploadFile
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocalUploadCapabilityLifecycleTest {
    @Test
    fun `selected capability is released when cancellation wins result delivery`() {
        val file = localUploadFile(
            selectionId = "selection-1234567890",
            displayName = "cancelled.txt",
            mimeType = "text/plain",
            sizeBytes = 12L,
        )
        val dispatcher = PausedDispatcher()
        var resumeSelection: ((LocalUploadSelectionResult) -> Unit)? = null
        var delivered = false
        var persistedCapability: LocalUploadFile? = file
        var cachedCapability: LocalUploadFile? = file
        val scopeJob = Job()
        val selectionJob = CoroutineScope(scopeJob + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<LocalUploadSelectionResult> { continuation ->
                resumeSelection = { result ->
                    resumeLocalUploadSelectionResult(
                        continuation = continuation,
                        result = result,
                        releaseSelected = { cancelledFile ->
                            assertEquals(cachedCapability, cancelledFile)
                            persistedCapability = null
                            cachedCapability = null
                        },
                    )
                }
            }
            delivered = true
        }

        checkNotNull(resumeSelection)(LocalUploadSelectionResult.Selected(file))
        selectionJob.cancel()
        dispatcher.runAll()

        assertTrue(selectionJob.isCancelled)
        assertFalse(delivered)
        assertEquals(null, persistedCapability)
        assertEquals(null, cachedCapability)
        scopeJob.cancel()
    }

    @Test
    fun `non-selected results do not request capability cleanup when delivery is cancelled`() {
        listOf(
            LocalUploadSelectionResult.Cancelled,
            LocalUploadSelectionResult.Rejected("synthetic rejection"),
        ).forEach { result ->
            val dispatcher = PausedDispatcher()
            var resumeSelection: (() -> Unit)? = null
            var releases = 0
            val scopeJob = Job()
            val selectionJob = CoroutineScope(scopeJob + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<LocalUploadSelectionResult> { continuation ->
                    resumeSelection = {
                        resumeLocalUploadSelectionResult(
                            continuation = continuation,
                            result = result,
                            releaseSelected = { releases += 1 },
                        )
                    }
                }
            }

            checkNotNull(resumeSelection).invoke()
            selectionJob.cancel()
            dispatcher.runAll()

            assertTrue(selectionJob.isCancelled)
            assertEquals(0, releases)
            scopeJob.cancel()
        }
    }

    @Test
    fun `permission is taken before metadata commit and retained after success`() {
        val events = mutableListOf<String>()

        acquireDurableUploadCapability(
            takePermission = { events += "permission" },
            persistMetadata = {
                events += "metadata"
                true
            },
            releasePermission = { events += "release" },
        )

        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata commit rolls back the persisted uri permission`() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                takePermission = { events += "permission" },
                persistMetadata = {
                    events += "metadata"
                    false
                },
                releasePermission = { events += "release" },
            )
        }

        assertEquals(listOf("permission", "metadata", "release"), events)
    }

    @Test
    fun `permission release precedes synchronous metadata deletion`() {
        val events = mutableListOf<String>()

        val released = releaseDurableUploadCapability(
            releasePermission = { events += "permission" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata deletion still revokes permission`() {
        var permissionReleased = false

        val released = releaseDurableUploadCapability(
            releasePermission = { permissionReleased = true },
            removeMetadata = { false },
        )

        assertFalse(released)
        assertTrue(permissionReleased)
    }

    @Test
    fun `uncached unreadable encrypted metadata is retained without claiming capability release`() {
        var encryptedMetadata: String? = "unreadable-encrypted-capability"
        var permissionReleased = false
        var cleanupPending = true

        val result = resultAfterDurableUploadCapabilityRelease(
            releaseCapability = {
                releaseStoredDurableUploadCapability<String>(
                    cachedCapability = null,
                    loadCapability = { throw GeneralSecurityException("synthetic decryption failure") },
                    releasePermission = { permissionReleased = true },
                    removeMetadata = { true.also { encryptedMetadata = null } },
                )
            },
            completeCapabilityCleanup = { cleanupPending = false },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("retry", result)
        assertTrue(cleanupPending)
        assertFalse(permissionReleased)
        assertEquals("unreadable-encrypted-capability", encryptedMetadata)
    }

    @Test
    fun `restored capability release revokes permission before deleting metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = null,
            loadCapability = {
                events += "restore"
                "content://synthetic/upload"
            },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("restore", "permission:content://synthetic/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `missing capability metadata is an idempotent cleanup success`() {
        var metadataRemovals = 0

        val released = releaseStoredDurableUploadCapability<String>(
            cachedCapability = null,
            loadCapability = { null },
            releasePermission = { error("Missing metadata has no URI grant to release.") },
            removeMetadata = {
                metadataRemovals += 1
                true
            },
        )

        assertTrue(released)
        assertEquals(1, metadataRemovals)
    }

    @Test
    fun `cached capability releases without reading redundant stored metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = "content://cached/upload",
            loadCapability = { error("Cached cleanup must not read stored metadata.") },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("permission:content://cached/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `capability restore preserves cancellation`() {
        assertFailsWith<CancellationException> {
            releaseStoredDurableUploadCapability<String>(
                cachedCapability = null,
                loadCapability = { throw CancellationException("cleanup stopped") },
                releasePermission = {},
                removeMetadata = { true },
            )
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
