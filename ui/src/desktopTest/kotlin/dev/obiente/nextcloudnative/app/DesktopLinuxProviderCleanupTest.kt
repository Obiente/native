package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        assertSame(provider, cleanup.pendingForTest())
        assertFailsWith<LinuxVirtualFileSystemException> { fileSystem.beginMutation() }
    }

    @Test
    fun `failed unmount without an abort handle still retains a quiesced provider`() {
        val fileSystem = RecordingLinuxProviderFileSystem(abortHandle = null)
        val provider = DetachedDesktopLinuxProvider(fileSystem, null, "account")
        val cleanup = DesktopLinuxProviderCleanupSlot()

        assertFailsWith<IllegalStateException> { cleanup.unmountOrRetain(provider) }

        assertSame(provider, cleanup.pendingForTest())
        assertFailsWith<LinuxVirtualFileSystemException> { fileSystem.beginMutation() }
    }
}

private class RecordingLinuxProviderFileSystem(
    private val abortHandle: LinuxFuseAbortHandle?,
) : DesktopLinuxProviderFileSystem {
    private val writeLifecycle = LinuxVirtualWriteLifecycle(
        hasOpenWriteHandles = { false },
        hasPendingCreatedFiles = { false },
    ).also { check(it.tryQuiesce()) }

    override fun unmount() = runLinuxFuseUnmountLifecycle(
        abortHandle = abortHandle,
        detach = { error("synthetic unmount failure") },
        cleanup = {},
    )

    fun beginMutation() = writeLifecycle.beginMutation()
}
