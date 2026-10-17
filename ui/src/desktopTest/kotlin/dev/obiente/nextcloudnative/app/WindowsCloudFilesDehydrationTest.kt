package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Free up space, automatic cleanup and the storage summary on connected placeholders. While the
 * provider is connected, every file placeholder is a cloud reparse point that Java NIO reports as
 * "other". Native cases run only on Windows, against a disposable sync root and a synthetic server.
 */
class WindowsCloudFilesDehydrationTest {
    private fun dehydratable(attributes: Int, tag: Int, state: WindowsCloudPlaceholderEntryState): Boolean {
        val inspection = WindowsCloudPlaceholderInspection(state)
        return windowsCloudDehydratable(windowsCloudLocalEntry(Path.of("synthetic-entry"), attributes, tag, inspection), attributes)
    }

    @Test
    fun `only an unpinned in-sync file placeholder is dehydratable`() {
        // Attributes and tags as measured on a connected Windows 11 sync root.
        assertTrue(dehydratable(HYDRATED, HYDRATED_TAG, WindowsCloudPlaceholderEntryState.InSync))
        assertTrue(dehydratable(DEHYDRATED, DEHYDRATED_TAG, WindowsCloudPlaceholderEntryState.InSync))
        assertFalse(dehydratable(HYDRATED, DIRTY_TAG, WindowsCloudPlaceholderEntryState.Dirty))
        assertFalse(dehydratable(HYDRATED or FILE_ATTRIBUTE_PINNED, HYDRATED_TAG, WindowsCloudPlaceholderEntryState.InSync))
        // A plain file is not a placeholder, and links, folders and foreign reparse points are not files.
        assertFalse(dehydratable(FILE_ATTRIBUTE_ARCHIVE, 0, WindowsCloudPlaceholderEntryState.Local))
        assertFalse(dehydratable(FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_SYMLINK, WindowsCloudPlaceholderEntryState.InSync))
        assertFalse(dehydratable(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, HYDRATED_TAG, WindowsCloudPlaceholderEntryState.InSync))
        assertFalse(dehydratable(HYDRATED, IO_REPARSE_TAG_DEDUP, WindowsCloudPlaceholderEntryState.InSync))
    }

    @Test
    fun `Windows refusals of dirty, pinned and open content free nothing`() {
        assertTrue(isWindowsCloudDehydrationRefusedResult(0x80070179.toInt()))
        assertTrue(isWindowsCloudDehydrationRefusedResult(0x80070187.toInt()))
        assertTrue(isWindowsCloudDehydrationRefusedResult(0x80070188.toInt()))
        // The exclusive oplock is unavailable or broke before dehydration.
        assertTrue(isWindowsCloudDehydrationRefusedResult(0x80070020.toInt()))
        assertTrue(isWindowsCloudDehydrationRefusedResult(0x80070323.toInt()))
        assertFalse(isWindowsCloudDehydrationRefusedResult(0))
        assertFalse(isWindowsCloudDehydrationRefusedResult(0x80070005.toInt())) // E_ACCESSDENIED
        assertFalse(isWindowsCloudDehydrationRefusedResult(0x8007016B.toInt())) // Metadata corrupt
    }

    @Test
    fun `a planned eviction is kept when its file changed after planning`() {
        val planned = WindowsCloudFileIdentity("account-01", "planned.bin", "\"etag-01\"", 64L, false)
        val api = EvictionApi(allocated = 64L)
        fun execute(current: WindowsCloudFileIdentity?) = api.dehydratePlannedEvictions(listOf(eviction(planned))) { relative ->
            current?.takeIf { it.path == relative }?.let { it to Path.of(it.path) }
        }

        // A same-size newer revision replaced the planned one, so its access history is unknown.
        assertEquals(0L, execute(planned.copy(remoteRevision = "\"etag-02\"")))
        assertEquals(0L, execute(null))
        api.pinned = true
        assertEquals(0L, execute(planned))
        api.pinned = false
        api.state = WindowsCloudPlaceholderState.Dirty
        assertEquals(0L, execute(planned))
        api.state = WindowsCloudPlaceholderState.InSync
        api.allocated = 128L
        assertEquals(0L, execute(planned))
        assertEquals(emptyList(), api.dehydrated)

        api.allocated = 64L
        assertEquals(64L, execute(planned))
        assertEquals(listOf(Path.of("planned.bin")), api.dehydrated)
        // Freed bytes are what dehydration released, not what the plan expected.
        api.released = 0L
        assertEquals(0L, execute(planned))
    }

    @Test
    fun `native Windows frees up space from a hydrated placeholder that still opens`() =
        withNativeRoot { root, _, api, provider ->
            val photo = root.resolve("photo.bin")
            assertContentEquals(CONTENT, Files.readAllBytes(photo))
            val allocated = awaitAllocated(api, photo)
            provider.summary().let { summary ->
                assertEquals(1, summary.hydratedFileCount)
                assertEquals(allocated, summary.cachedBytes)
                assertEquals(allocated, summary.reclaimableBytes)
            }

            assertEquals(allocated, provider.freeUpSpace(requestedBytes = 0L))

            assertEquals(0L, api.allocatedBytes(photo))
            assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(photo))
            assertEquals(0L, provider.summary().cachedBytes)
            assertContentEquals(CONTENT, Files.readAllBytes(photo))
            assertEquals(allocated, awaitAllocated(api, photo))
        }

    @Test
    fun `native Windows automatic cleanup dehydrates content over its budget`() =
        withNativeRoot { root, _, api, provider ->
            val photo = root.resolve("photo.bin")
            assertContentEquals(CONTENT, Files.readAllBytes(photo))
            val allocated = awaitAllocated(api, photo)
            val policy = VirtualFileCachePolicy(maximumCacheBytes = 1L, minimumFreeSpaceBytes = 0L, unusedFileAgeMillis = null)

            assertEquals(allocated, provider.enforcePolicy(policy))

            assertEquals(0L, api.allocatedBytes(photo))
            assertContentEquals(CONTENT, Files.readAllBytes(photo))
        }

    @Test
    fun `native Windows keeps dirty, pinned and open content local`() =
        withNativeRoot { root, backend, api, provider ->
            val draft = root.resolve("draft.bin")
            val pinned = root.resolve("pinned.bin")
            val open = root.resolve("open.bin")
            listOf(draft, pinned, open).forEach { assertContentEquals(CONTENT, Files.readAllBytes(it)) }
            runExternalPowerShell("attrib +P \$env:NATIVE_PIN_PATH", mapOf("NATIVE_PIN_PATH" to pinned.toAbsolutePath().toString()))
            assertTrue(api.isPinned(pinned))
            // The server stays unavailable, so the edit remains the only copy.
            backend.unavailableUploads = Int.MAX_VALUE
            editInPlaceFromAnotherProcess(draft, EDIT)
            awaitCondition({ "dirty draft" }) { api.placeholderState(draft) == WindowsCloudPlaceholderState.Dirty }
            val allocated = listOf(draft, pinned, open).associateWith { awaitAllocated(api, it) }

            // An application that denies write sharing keeps the file open; Windows reports it in use.
            ExternalOpenHandle(open, share = "Read").use {
                assertEquals(0L, api.dehydrate(open))
                assertEquals(0L, provider.freeUpSpace(requestedBytes = 0L))
                assertEquals(0L, provider.enforcePolicy(VirtualFileCachePolicy(maximumCacheBytes = 1L, minimumFreeSpaceBytes = 0L)))
            }
            assertEquals(0L, api.dehydrate(draft))
            assertEquals(0L, api.dehydrate(pinned))

            assertEquals(allocated, listOf(draft, pinned, open).associateWith(api::allocatedBytes))
            assertEquals(WindowsCloudPlaceholderState.Dirty, api.placeholderState(draft))
            assertContentEquals(EDIT, Files.readAllBytes(draft).copyOf(EDIT.size))
            provider.summary().let { summary ->
                assertEquals(allocated.values.sum(), summary.cachedBytes)
                assertEquals(allocated.getValue(open), summary.reclaimableBytes)
                assertEquals(allocated.getValue(pinned), summary.pinnedBytes)
            }
        }

    private fun eviction(identity: WindowsCloudFileIdentity) = VirtualFileEviction(
        key = FileOfflineKey(identity.accountId, identity.path),
        expectedLocalRevision = identity.remoteRevision,
        sizeBytes = identity.size,
        reasons = setOf(VirtualFileEvictionReason.CacheBudget),
    )

    /** Only the checks an eviction executor makes; every other Cloud Files call is unexpected. */
    private class EvictionApi(
        var allocated: Long,
    ) : WindowsCloudFilesApi by WindowsCloudFilesProviderTest.FakeApi() {
        var pinned = false
        var state = WindowsCloudPlaceholderState.InSync
        var released = allocated
        val dehydrated = mutableListOf<Path>()

        override fun isPinned(path: Path) = pinned
        override fun placeholderState(path: Path) = state
        override fun allocatedBytes(path: Path) = allocated
        override fun dehydrate(path: Path): Long = released.also { dehydrated.add(path) }
    }

    private fun withNativeRoot(
        block: (
            Path,
            WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend,
            JnaWindowsCloudFilesApi,
            WindowsCloudFilesProvider,
        ) -> Unit,
    ) {
        assumeTrue(isWindowsDesktop())
        val root = createTempDirectory("cloud-native-dehydration-")
        val backend = WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend(
            *listOf("photo.bin", "draft.bin", "pinned.bin", "open.bin").map { it to CONTENT }.toTypedArray(),
        )
        val api = JnaWindowsCloudFilesApi(shellRegistrar = PackagedWindowsCloudShellRegistrar(launcherPath = null))
        // Failed uploads are not retried during a test, so a dirty edit stays dirty.
        val provider = WindowsCloudFilesProvider(root, backend, api, writebackRetryDelayMillis = { TimeUnit.HOURS.toMillis(1) })
        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            block(root, backend, api, provider)
        } finally {
            try {
                provider.removeSyncRoot()
            } finally {
                provider.close()
                root.toFile().deleteRecursively()
            }
        }
    }

    /**
     * Waits, within a bound, until a hydrated file reports its whole size as allocated. Reading the
     * content does not guarantee that a single allocation sample already includes every cluster.
     */
    private fun awaitAllocated(api: JnaWindowsCloudFilesApi, path: Path): Long {
        awaitCondition({ "allocated bytes of ${path.fileName}: ${allocationDiagnostics(api, path)}" }) {
            api.allocatedBytes(path) >= CONTENT.size
        }
        return api.allocatedBytes(path)
    }

    private fun allocationDiagnostics(api: JnaWindowsCloudFilesApi, path: Path): String {
        val high = IntByReference()
        val low = Native.load("kernel32", KernelFileApi::class.java)
            .GetCompressedFileSizeW(WString(path.toWindowsWin32Path()), high)
        val error = Native.getLastError()
        return "reported=${api.allocatedBytes(path)}, raw=$low/${high.value}, error=$error, " +
            "pinned=${api.isPinned(path)}, inspection=${api.inspectPlaceholder(path)}"
    }

    private fun awaitCondition(label: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for ${label()}." }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        const val FILE_ATTRIBUTE_ARCHIVE = 0x20
        const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
        const val FILE_ATTRIBUTE_PINNED = 0x0008_0000
        const val HYDRATED = FILE_ATTRIBUTE_ARCHIVE or FILE_ATTRIBUTE_REPARSE_POINT
        const val DEHYDRATED = 0x0040_1620 // Adds sparse, offline and recall-on-data-access.
        const val HYDRATED_TAG = 0x9000601A.toInt()
        const val DEHYDRATED_TAG = 0x9000401A.toInt()
        const val DIRTY_TAG = 0x9000201A.toInt()
        const val IO_REPARSE_TAG_SYMLINK = 0xA000000C.toInt()
        const val IO_REPARSE_TAG_DEDUP = 0x80000013.toInt()

        // Large enough that a hydrated placeholder allocates whole clusters.
        val CONTENT = ByteArray(256 * 1024) { (it % 251).toByte() }
        val EDIT = "synthetic local edit".encodeToByteArray()
    }
}
