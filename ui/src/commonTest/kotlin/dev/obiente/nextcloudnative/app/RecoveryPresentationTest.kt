package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RecoveryPresentationTest {
    @Test
    fun connectionAvailabilityDoesNotClaimAnActiveConnection() {
        val base = defaultVirtualFileStorageSnapshot().copy(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.WindowsCloudFiles,
            providerState = VirtualFileProviderState.Inactive,
        )
        assertEquals("Not connected", base.virtualStorageStatusLabel())
        val active = base.copy(providerState = VirtualFileProviderState.Active, providerActive = true)
        assertEquals("Connected", active.virtualStorageStatusLabel())
        assertEquals("Local edits pending", active.copy(pendingWritebackCount = 1).virtualStorageStatusLabel())
        assertEquals("Edits need review", active.copy(pendingWritebackCount = 1,
            providerState = VirtualFileProviderState.NeedsAttention).virtualStorageStatusLabel())
        assertEquals("Edits need review", active.copy(providerRecoveryNotice = "Copies retained").virtualStorageStatusLabel())
        assertEquals("Connection needs attention", active.copy(providerState = VirtualFileProviderState.NeedsAttention).virtualStorageStatusLabel())
    }

    @Test
    fun transferLabelsDistinguishWaitingFailureAndVerifiedCompletion() {
        val local = LocalMediaObject("photo-1", "Example.jpg", 100, "revision-1")
        val pending = MediaBackupLedgerRecord("synthetic-account", local = local, receipt = null,
            transferState = MediaBackupTransferState.Pending, attemptCount = 0, updatedAtEpochMillis = 0)
        assertEquals("Waiting to upload", mediaTransferProgressLabel(pending))
        assertEquals("Uploading", mediaTransferProgressLabel(pending.copy(transferState = MediaBackupTransferState.Uploading)))
        assertEquals("2 failed attempts", mediaTransferProgressLabel(pending.copy(
            transferState = MediaBackupTransferState.Failed, attemptCount = 2, failureMessage = "Connection lost")))
        val complete = pending.copy(transferState = MediaBackupTransferState.Succeeded,
            receipt = MediaBackupReceipt(local.key, local.revision, local.size, "Photos/Example.jpg", "etag-1", 0))
        assertEquals("Verified 1970-01-01 00:00 UTC", mediaTransferProgressLabel(complete))
    }
}
