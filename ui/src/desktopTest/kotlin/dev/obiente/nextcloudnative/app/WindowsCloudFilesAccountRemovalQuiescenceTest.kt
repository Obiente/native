package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCloudFilesAccountRemovalQuiescenceTest {
    @Test
    fun `dirty close crossing the pause is uploaded before account removal continues`() {
        val root = createTempDirectory("windows-cloud-account-removal-")
        val local = root.resolve("draft.txt")
        val bytes = "edit closed while sign-out starts".encodeToByteArray()
        val unmanaged = root.resolve("new-note.txt")
        val unmanagedBytes = "edit whose watcher debounce crossed sign-out".encodeToByteArray()
        val identity = WindowsCloudFileIdentity("account-01", "draft.txt", "etag-1", bytes.size.toLong(), false)
        val backend = WindowsCloudFilesProviderTest.FakeBackend(ByteArray(0), expectedUploads = 2)
        val api = WindowsCloudFilesProviderTest.FakeApi(expectedConversions = 2)
        lateinit var provider: WindowsCloudFilesProvider
        try {
            provider = WindowsCloudFilesProvider(root, backend, api)
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5L)
            local.writeBytes(bytes)
            unmanaged.writeBytes(unmanagedBytes)
            api.beforeDisconnect = {
                api.seed(local, WindowsCloudPlaceholderState.Dirty, identity)
                provider.closed(callbackInfo(local, identity), deleted = false)
                provider.localEntryChanged(unmanaged)
            }

            assertTrue(provider.quiesceWritesForAccountRemoval(timeoutSeconds = 5L))

            assertTrue(backend.awaitUploads())
            assertEquals(
                setOf(bytes.toList(), unmanagedBytes.toList()),
                backend.uploadedBytes.map(ByteArray::toList).toSet(),
            )
            assertEquals(listOf(1L), api.disconnectAttempts)
            assertEquals(null, api.unregisteredRoot)
            provider.removeSyncRoot()
            assertEquals(root, api.unregisteredRoot)
        } finally {
            if (!api.closed) runCatching { provider.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quiescence drains an admitted destructive callback and rejects later callbacks`() {
        val root = createTempDirectory("windows-cloud-account-removal-delete-")
        val identity = WindowsCloudFileIdentity("account-01", "note.txt", "etag-2", 0L, false)
        val backend = WindowsCloudFilesProviderTest.FakeBackend(
            ByteArray(0),
            listed = listOf(identity),
            blockFirstDelete = true,
        )
        val api = WindowsCloudFilesProviderTest.FakeApi()
        val provider = WindowsCloudFilesProvider(root, backend, api)
        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5L)
            val info = callbackInfo(root.resolve(identity.path), identity)
            provider.deleteRequested(info)
            assertTrue(backend.awaitFirstDeleteStarted())
            val disconnected = CountDownLatch(1)
            api.beforeDisconnect = { disconnected.countDown() }
            val failure = AtomicReference<Throwable?>()
            val quiescence = Thread {
                try {
                    provider.quiesceWritesForAccountRemoval(timeoutSeconds = 5L)
                } catch (thrown: Throwable) {
                    failure.set(thrown)
                }
            }
            quiescence.start()
            assertFalse(disconnected.await(250L, TimeUnit.MILLISECONDS))
            assertTrue(quiescence.isAlive)
            backend.releaseFirstDelete()
            assertTrue(disconnected.await(5L, TimeUnit.SECONDS))
            quiescence.join(5_000L)

            assertFalse(quiescence.isAlive)
            assertEquals(null, failure.get())
            provider.deleteRequested(info)
            assertEquals(listOf("delete:note.txt"), backend.operations)
            provider.resumeWritesAfterAccountRemovalFailure()
            assertEquals(2, api.lifecycleEvents.count { it == "connect" })
        } finally {
            backend.releaseFirstDelete()
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unrecoverable writeback reopens callbacks instead of continuing removal`() {
        var resumed = false
        val quiescence = WindowsCloudFilesRemovalQuiescence(
            pauseCallbacks = { true },
            mutationState = {
                WindowsCloudFilesMutationState(1, 1, 0, 0, 0)
            },
            resumeCallbacks = { resumed = true },
        )

        assertFailsWith<IllegalStateException> { quiescence.tryQuiesce(timeoutSeconds = 1L) }

        assertTrue(resumed)
    }

    private fun callbackInfo(local: java.nio.file.Path, identity: WindowsCloudFileIdentity) =
        WindowsCloudCallbackInfo(
            connectionKey = 1L,
            transferKey = 2L,
            requestKey = 3L,
            normalizedPath = local.toString(),
            fileIdentity = WindowsCloudFileIdentityCodec.encode(identity),
            fileSize = identity.size,
            priorityHint = 0,
        )
}
