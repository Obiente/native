package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VirtualFileStorageCenterTest {
    @Test
    fun `unsupported default cannot imply cached content or an integration`() {
        val snapshot = defaultVirtualFileStorageSnapshot()
        assertEquals(VirtualFileStorageSupport.Unsupported, snapshot.support)
        assertEquals(null, snapshot.integration)
        assertEquals(0L, snapshot.cachedBytes)
    }

    @Test
    fun `snapshot rejects impossible storage and reclaimable values`() {
        val base = VirtualFileStorageSnapshot(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.AndroidDocumentsProvider,
            policy = VirtualFileCachePolicy(),
            cachedBytes = 100L,
            reclaimableBytes = 80L,
            pinnedBytes = 20L,
            hydratedFileCount = 2,
            pinnedFileCount = 1,
            availableFreeBytes = 900L,
            storageCapacityBytes = 1_000L,
        )
        assertEquals(80L, base.reclaimableBytes)
        assertFailsWith<IllegalArgumentException> { base.copy(reclaimableBytes = 101L) }
        assertFailsWith<IllegalArgumentException> { base.copy(availableFreeBytes = 1_001L) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(
                support = VirtualFileStorageSupport.Unsupported,
                integration = VirtualFilePlatformIntegration.AndroidDocumentsProvider,
            )
        }
    }

    @Test
    fun `provider connectivity remains independent from recovery attention`() {
        val snapshot = VirtualFileStorageSnapshot(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.WindowsCloudFiles,
            policy = VirtualFileCachePolicy(),
            cachedBytes = 0L,
            reclaimableBytes = 0L,
            pinnedBytes = 0L,
            hydratedFileCount = 0,
            pinnedFileCount = 0,
            availableFreeBytes = null,
            storageCapacityBytes = null,
            providerState = VirtualFileProviderState.NeedsAttention,
            providerActive = true,
            providerRecoveryNotice = "Existing local data was preserved for review.",
        )

        assertTrue(snapshot.providerActive)
        assertFailsWith<IllegalArgumentException> {
            snapshot.copy(providerState = VirtualFileProviderState.Inactive)
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot.copy(providerState = VirtualFileProviderState.Active, providerActive = false)
        }
    }
}
