package dev.obiente.nextcloudnative.app

/**
 * Stable local identity supplied by MediaStore or an equivalent platform media library.
 *
 * [revision] must change whenever the local bytes change. Android can use MediaStore generation
 * values where available and a size/modified fallback on older releases.
 */
data class LocalMediaObject(
    val key: String,
    val displayName: String,
    val size: Long,
    val revision: String,
) {
    init {
        require(key.isSafeMediaLedgerText(2_048))
        require(displayName.isSafeMediaLedgerText(512))
        require(size >= 0L)
        require(revision.isSafeMediaLedgerText(512))
    }
}

/**
 * Durable proof that one exact local revision completed an upload and was verified remotely.
 *
 * A receipt is not reusable after the local revision or size changes.
 */
data class MediaBackupReceipt(
    val localKey: String,
    val localRevision: String,
    val localSize: Long,
    val remotePath: String,
    val remoteEtag: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        require(localKey.isSafeMediaLedgerText(2_048))
        require(localRevision.isSafeMediaLedgerText(512))
        require(localSize >= 0L)
        requireValidSyncPath(remotePath)
        require(remoteEtag.isSafeMediaLedgerText(512))
        require(verifiedAtEpochMillis >= 0L)
    }
}

enum class MediaBackupStatus {
    Pending,
    Uploading,
    BackedUp,
    ChangedAfterBackup,
    Failed,
    CloudOnly,
}

fun resolveMediaBackupStatus(
    local: LocalMediaObject?,
    receipt: MediaBackupReceipt?,
    uploading: Boolean = false,
    failed: Boolean = false,
): MediaBackupStatus {
    if (uploading && local != null) return MediaBackupStatus.Uploading
    if (failed && local != null) return MediaBackupStatus.Failed
    if (local == null) return if (receipt == null) MediaBackupStatus.Pending else MediaBackupStatus.CloudOnly
    if (receipt == null || receipt.localKey != local.key) return MediaBackupStatus.Pending
    return if (receipt.matches(local)) MediaBackupStatus.BackedUp else MediaBackupStatus.ChangedAfterBackup
}

sealed interface MediaReclaimEligibility {
    data class Eligible(val bytes: Long) : MediaReclaimEligibility {
        init {
            require(bytes >= 0L)
        }
    }

    data object NotBackedUp : MediaReclaimEligibility
    data object LocalCopyChanged : MediaReclaimEligibility
    data object AlreadyCloudOnly : MediaReclaimEligibility
}

/**
 * Determines whether Android may offer a local deletion confirmation.
 *
 * Actual deletion still requires an explicit user-reviewed MediaStore delete request. This method
 * only establishes that the current local bytes exactly match a verified remote receipt.
 */
fun mediaReclaimEligibility(
    local: LocalMediaObject?,
    receipt: MediaBackupReceipt?,
): MediaReclaimEligibility {
    if (local == null) return MediaReclaimEligibility.AlreadyCloudOnly
    if (receipt == null || receipt.localKey != local.key) return MediaReclaimEligibility.NotBackedUp
    if (!receipt.matches(local)) return MediaReclaimEligibility.LocalCopyChanged
    return MediaReclaimEligibility.Eligible(local.size)
}

private fun MediaBackupReceipt.matches(local: LocalMediaObject): Boolean =
    localKey == local.key && localRevision == local.revision && localSize == local.size

private fun String.isSafeMediaLedgerText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
