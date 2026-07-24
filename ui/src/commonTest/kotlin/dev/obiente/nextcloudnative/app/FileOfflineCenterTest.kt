package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOfflineCenterTest {
    @Test
    fun `safe defaults distinguish unsupported storage from unavailable inventory`() {
        val unsupported = defaultFileOfflineCenterSnapshot(supportsIndividualOfflineFiles = false)
        val inventoryUnavailable = defaultFileOfflineCenterSnapshot(supportsIndividualOfflineFiles = true)
        val recursiveInventoryUnavailable = defaultFileOfflineCenterSnapshot(
            supportsIndividualOfflineFiles = true,
            supportsRecursiveFolderAvailability = true,
        )

        assertEquals(FileOfflineCenterSupport.Unsupported, unsupported.support)
        assertEquals(FileOfflineCenterSupport.InventoryUnavailable, inventoryUnavailable.support)
        assertTrue(unsupported.items.isEmpty())
        assertTrue(inventoryUnavailable.items.isEmpty())
        assertTrue(unsupported.limitations.any { "not implemented" in it })
        assertTrue(inventoryUnavailable.limitations.any { "not exposed" in it })
        assertTrue(inventoryUnavailable.limitations.none { "bidirectional" in it.lowercase() && "available" in it.lowercase() })
        assertEquals(
            FileOfflineFolderAvailability.RecursiveDownloadOnly,
            recursiveInventoryUnavailable.folderAvailability,
        )
        assertTrue(recursiveInventoryUnavailable.limitations.any { "Files and folders" in it })
        assertTrue(recursiveInventoryUnavailable.limitations.any { "does not upload" in it })
        assertTrue(recursiveInventoryUnavailable.limitations.any { "not implemented" in it })
    }

    @Test
    fun `queue snapshot is account scoped status aware and storage honest`() {
        val account = "account-a"
        val available = record(
            account = account,
            path = "Notes/vault.md",
            size = 1_024L,
            localRevision = "sha256:available",
            syncedEtag = "\"v1\"",
        )
        val failed = record(account, "Media/photo.jpg", 2_048L)
        val conflict = record(
            account = account,
            path = "Documents/report.pdf",
            size = null,
            attention = FileSyncDecisionReason.SimultaneousEdit,
        )
        val otherAccount = record(
            account = "account-b",
            path = "Private/other.txt",
            size = 4_096L,
            localRevision = "sha256:other",
            syncedEtag = "\"other\"",
        )
        val failedJob = FileOfflineJob(
            id = 1L,
            key = failed.descriptor.key,
            operation = FileOfflineJobOperation.Download,
            expectedRemoteEtag = failed.descriptor.remoteEtag,
            expectedLocalRevision = null,
            status = FileOfflineJobStatus.Failed,
            attemptCount = 2,
            enqueuedAtEpochMillis = 20L,
            failureMessage = "Server unavailable",
        )
        val state = FileOfflineQueueState(
            records = listOf(available, failed, conflict, otherAccount),
            jobs = listOf(failedJob),
            nextJobId = 2L,
        )

        val snapshot = fileOfflineCenterSnapshot(
            state = state,
            accountId = account,
            allowRetry = true,
            allowRemove = true,
            storageCapacityBytes = 8_192L,
            supportsRecursiveFolderAvailability = true,
        )

        assertEquals(FileOfflineCenterSupport.Available, snapshot.support)
        assertEquals(
            listOf("report.pdf", "photo.jpg", "vault.md"),
            snapshot.items.map(FileOfflineCenterItem::displayName),
        )
        assertEquals(FileOfflineAvailability.NeedsAttention, snapshot.items[0].availability)
        assertTrue(snapshot.items[0].detail.orEmpty().contains("changed independently"))
        assertFalse(snapshot.items[0].canRetry)
        assertEquals(FileOfflineAvailability.Failed, snapshot.items[1].availability)
        assertEquals("Server unavailable", snapshot.items[1].detail)
        assertTrue(snapshot.items[1].canRetry)
        assertTrue(snapshot.items.all(FileOfflineCenterItem::canRemove))
        assertEquals(1_024L, snapshot.storageUsage?.usedBytes)
        assertEquals(8_192L, snapshot.storageUsage?.capacityBytes)
        assertTrue(snapshot.storageUsage?.estimated == true)
        assertEquals(FileOfflineFolderAvailability.RecursiveDownloadOnly, snapshot.folderAvailability)
        assertTrue(snapshot.limitations.any { "downloaded recursively" in it })
        assertTrue(snapshot.limitations.any { "does not upload" in it })
        assertTrue(snapshot.limitations.any { "Bidirectional folder" in it })
    }

    @Test
    fun `unknown sizes remain an estimate and action affordances are conservative`() {
        val record = record(
            account = "account-a",
            path = "Archive/unknown.bin",
            size = null,
            localRevision = "sha256:unknown",
            syncedEtag = "\"remote\"",
        )
        val snapshot = fileOfflineCenterSnapshot(
            state = FileOfflineQueueState(records = listOf(record)),
            accountId = "account-a",
            allowRetry = false,
            allowRemove = false,
        )

        assertEquals(0L, snapshot.storageUsage?.usedBytes)
        assertNull(snapshot.storageUsage?.capacityBytes)
        assertTrue(snapshot.storageUsage?.estimated == true)
        assertFalse(snapshot.items.single().canRetry)
        assertFalse(snapshot.items.single().canRemove)
    }

    @Test
    fun `invalid partial inventories and unsafe retry claims are rejected`() {
        val key = FileOfflineKey("account-a", "Notes/file.md")
        val available = FileOfflineCenterItem(
            key = key,
            displayName = "file.md",
            sizeBytes = 10L,
            availability = FileOfflineAvailability.Available,
            detail = null,
            canRetry = false,
            canRemove = true,
        )

        assertFailsWith<IllegalArgumentException> {
            available.copy(canRetry = true)
        }
        assertFailsWith<IllegalArgumentException> {
            FileOfflineCenterSnapshot(
                support = FileOfflineCenterSupport.InventoryUnavailable,
                items = listOf(available),
                storageUsage = null,
                limitations = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FileOfflineStorageUsage(usedBytes = 20L, capacityBytes = 10L, estimated = false)
        }
    }

    private fun record(
        account: String,
        path: String,
        size: Long?,
        localRevision: String? = null,
        syncedEtag: String? = null,
        attention: FileSyncDecisionReason? = null,
    ) = FileOfflinePinRecord(
        descriptor = FileOfflineDescriptor(
            key = FileOfflineKey(account, path),
            displayName = path.substringAfterLast('/'),
            remoteEtag = "\"remote-${path.length}\"",
            size = size,
            mimeType = null,
        ),
        intent = FileOfflineIntent.Pinned,
        localRevision = localRevision,
        syncedRemoteEtag = syncedEtag,
        attentionReason = attention,
        updatedAtEpochMillis = 10L,
    )
}
