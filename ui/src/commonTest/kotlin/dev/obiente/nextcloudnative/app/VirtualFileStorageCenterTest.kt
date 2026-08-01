package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
