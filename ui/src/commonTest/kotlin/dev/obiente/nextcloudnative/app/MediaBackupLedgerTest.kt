package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaBackupLedgerTest {
    private val local = LocalMediaObject(
        key = "external:42",
        displayName = "IMG_0042.jpg",
        size = 4_096,
        revision = "generation:9",
    )
    private val receipt = MediaBackupReceipt(
        localKey = local.key,
        localRevision = local.revision,
        localSize = local.size,
        remotePath = "Photos/Camera/IMG_0042.jpg",
        remoteEtag = "\"remote-42\"",
        verifiedAtEpochMillis = 1_000,
    )

    @Test
    fun exactVerifiedRevisionIsBackedUpAndReclaimable() {
        assertEquals(MediaBackupStatus.BackedUp, resolveMediaBackupStatus(local, receipt))
        assertEquals(local.size, assertIs<MediaReclaimEligibility.Eligible>(
            mediaReclaimEligibility(local, receipt),
        ).bytes)
    }

    @Test
    fun changedLocalBytesCannotBeReclaimedUsingAnOldReceipt() {
        val changed = local.copy(revision = "generation:10")

        assertEquals(MediaBackupStatus.ChangedAfterBackup, resolveMediaBackupStatus(changed, receipt))
        assertEquals(MediaReclaimEligibility.LocalCopyChanged, mediaReclaimEligibility(changed, receipt))
    }

    @Test
    fun removedLocalOriginalRemainsRepresentedAsCloudOnly() {
        assertEquals(MediaBackupStatus.CloudOnly, resolveMediaBackupStatus(null, receipt))
        assertEquals(MediaReclaimEligibility.AlreadyCloudOnly, mediaReclaimEligibility(null, receipt))
    }
}
