package dev.obiente.nextcloudnative.app

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import ru.serce.jnrfuse.ErrorCodes

class DesktopLinuxProviderCleanupTest {
    @Test
    fun `failed unmount aborts fuse and retains the exact quiesced provider`() {
        var aborted = false
        var abortHandleClosed = false
        val fileSystem = RecordingLinuxProviderFileSystem(
            abortHandle = object : LinuxFuseAbortHandle {
                override fun abortBestEffort() {
                    aborted = true
                }

                override fun close() {
                    abortHandleClosed = true
                }
            },
        )
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertTrue(aborted)
        assertTrue(abortHandleClosed)
        assertTrue(fileSystem.readsDisabled)
        assertSame(provider, cleanup.pendingForTest())
        assertFailsWith<LinuxVirtualFileSystemException> { fileSystem.beginMutation() }
    }

    @Test
    fun `failed unmount without an abort handle still retains a quiesced provider`() {
        val fileSystem = RecordingLinuxProviderFileSystem(abortHandle = null)
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertTrue(fileSystem.readsDisabled)
        assertSame(provider, cleanup.pendingForTest())
        assertFailsWith<LinuxVirtualFileSystemException> { fileSystem.beginMutation() }
    }

    @Test
    fun `failed unmount rejects retained filesystem reads without reaching its backend`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        val backend = ReadCountingLinuxBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = backend,
            unmountOperation = { error("synthetic unmount failure") },
        )
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()
        assertEquals(0, fileSystem.access("/", 0))
        assertEquals(1, backend.resolveCalls)

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertEquals(-ErrorCodes.EIO(), fileSystem.access("/", 0))
        assertEquals(1, backend.resolveCalls)
        assertSame(provider, cleanup.pendingForTest())
    }
}

private class RecordingLinuxProviderFileSystem(
    private val abortHandle: LinuxFuseAbortHandle?,
) : DesktopLinuxProviderFileSystem {
    var readsDisabled = false
        private set
    private val writeLifecycle = LinuxVirtualWriteLifecycle(
        hasOpenWriteHandles = { false },
        hasPendingCreatedFiles = { false },
    ).also { check(it.tryQuiesce()) }

    override fun disableReads() {
        readsDisabled = true
    }

    override fun unmount() = runLinuxFuseUnmountLifecycle(
        abortHandle = abortHandle,
        detach = { error("synthetic unmount failure") },
        cleanup = {},
    )

    fun beginMutation() = writeLifecycle.beginMutation()
}

private class ReadCountingLinuxBackend : LinuxVirtualFileBackend {
    var resolveCalls = 0
        private set

    override fun resolve(path: String): LinuxVirtualFileNode? {
        resolveCalls += 1
        return LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root")
    }

    override fun list(path: String): List<LinuxVirtualFileNode> = error("Not used.")
    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle = error("Not used.")

    override fun openWrite(
        path: String,
        existing: LinuxVirtualFileNode?,
        truncate: Boolean,
    ): LinuxVirtualFileWriteHandle = error("Not used.")

    override fun createDirectory(path: String) = error("Not used.")
    override fun delete(node: LinuxVirtualFileNode) = error("Not used.")
    override fun move(node: LinuxVirtualFileNode, destinationPath: String) = error("Not used.")

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) = error("Not used.")
}
