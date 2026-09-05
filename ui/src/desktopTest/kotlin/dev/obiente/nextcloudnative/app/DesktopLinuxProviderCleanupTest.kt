package dev.obiente.nextcloudnative.app

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
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = CleanupBackend,
            unmountOperation = { error("synthetic unmount failure") },
            fuseAbortHandleProvider = {
                object : LinuxFuseAbortHandle {
                    override fun abortBestEffort() {
                        aborted = true
                    }

                    override fun close() {
                        abortHandleClosed = true
                    }
                }
            },
            mountOwnerUid = TEST_MOUNT_OWNER_UID,
            mountOwnerGid = TEST_MOUNT_OWNER_GID,
        )
        assertTrue(fileSystem.quiesceWrites())
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertTrue(aborted)
        assertTrue(abortHandleClosed)
        assertSame(provider, cleanup.pendingForTest())
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.mkdir("/blocked", 0L))
    }

    @Test
    fun `failed unmount without an abort handle still retains a quiesced provider`() {
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = CleanupBackend,
            unmountOperation = { error("synthetic unmount failure") },
            fuseAbortHandleProvider = { null },
            mountOwnerUid = TEST_MOUNT_OWNER_UID,
            mountOwnerGid = TEST_MOUNT_OWNER_GID,
        )
        assertTrue(fileSystem.quiesceWrites())
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertSame(provider, cleanup.pendingForTest())
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.mkdir("/blocked", 0L))
    }
}

private const val TEST_MOUNT_OWNER_UID = 2_001L
private const val TEST_MOUNT_OWNER_GID = 2_002L

private object CleanupBackend : LinuxVirtualFileBackend {
    override fun resolve(path: String): LinuxVirtualFileNode? = null
    override fun list(path: String): List<LinuxVirtualFileNode> = emptyList()
    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle = error("Not used")
    override fun openWrite(
        path: String,
        existing: LinuxVirtualFileNode?,
        truncate: Boolean,
    ): LinuxVirtualFileWriteHandle = error("Not used")

    override fun createDirectory(path: String) = Unit
    override fun delete(node: LinuxVirtualFileNode) = Unit
    override fun move(node: LinuxVirtualFileNode, destinationPath: String) = Unit
    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) = Unit

    override fun close() = Unit
}
