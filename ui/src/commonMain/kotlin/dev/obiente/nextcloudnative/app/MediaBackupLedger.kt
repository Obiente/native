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

enum class MediaBackupTransferState {
    Pending,
    Uploading,
    Failed,
    Succeeded,
}

data class MediaBackupLedgerRecord(
    val accountId: String,
    val sourceId: String? = null,
    val local: LocalMediaObject?,
    val receipt: MediaBackupReceipt?,
    val transferState: MediaBackupTransferState,
    val attemptCount: Int,
    val updatedAtEpochMillis: Long,
    val failureMessage: String? = null,
) {
    init {
        require(accountId.isSafeMediaLedgerText(256))
        require(sourceId == null || sourceId.isSafeMediaLedgerText(256))
        require(local != null || receipt != null)
        require(local == null || receipt == null || local.key == receipt.localKey)
        require(attemptCount in 0..MAX_MEDIA_BACKUP_ATTEMPTS)
        require(updatedAtEpochMillis >= 0L)
        require(failureMessage == null || failureMessage.isSafeMediaLedgerText(1_024))
        require((transferState == MediaBackupTransferState.Failed) == (failureMessage != null))
        require(transferState != MediaBackupTransferState.Uploading || local != null)
        require(transferState != MediaBackupTransferState.Pending || local != null)
        require(
            transferState != MediaBackupTransferState.Succeeded ||
                receipt != null && (local == null || receipt.matches(local)),
        )
    }

    val localKey: String
        get() = local?.key ?: requireNotNull(receipt).localKey
}

data class MediaBackupLedgerCursor(
    val updatedAtEpochMillis: Long,
    val localKey: String,
) {
    init {
        require(updatedAtEpochMillis >= 0L)
        require(localKey.isSafeMediaLedgerText(2_048))
    }
}

data class MediaBackupLedgerPage(
    val records: List<MediaBackupLedgerRecord>,
    val nextCursor: MediaBackupLedgerCursor?,
) {
    init {
        require(records.size <= MAX_MEDIA_BACKUP_LEDGER_PAGE_SIZE)
        require(records.map { it.accountId to it.localKey }.distinct().size == records.size)
    }
}

data class MediaBackupLedgerSummary(
    val pending: Int,
    val uploading: Int,
    val failed: Int,
    val succeeded: Int,
) {
    init {
        require(listOf(pending, uploading, failed, succeeded).all { it >= 0 })
    }

    val total: Int
        get() = pending + uploading + failed + succeeded
}

enum class MediaTransferSection {
    Pending,
    Active,
    Failed,
    Completed,
}

fun MediaTransferSection.transferState(): MediaBackupTransferState = when (this) {
    MediaTransferSection.Pending -> MediaBackupTransferState.Pending
    MediaTransferSection.Active -> MediaBackupTransferState.Uploading
    MediaTransferSection.Failed -> MediaBackupTransferState.Failed
    MediaTransferSection.Completed -> MediaBackupTransferState.Succeeded
}

internal fun boundedMediaTransferCursorHistory(
    history: List<MediaBackupLedgerCursor?>,
): List<MediaBackupLedgerCursor?> {
    require(history.isNotEmpty() && history.first() == null)
    if (history.size <= MAX_SAVED_MEDIA_TRANSFER_WINDOWS) return history
    return listOf(null) + history.drop(1).takeLast(MAX_SAVED_MEDIA_TRANSFER_WINDOWS - 1)
}

internal fun encodeMediaTransferCursorHistory(
    history: List<MediaBackupLedgerCursor?>,
): String = boundedMediaTransferCursorHistory(history).joinToString("\n") { cursor ->
    cursor?.let { "${it.updatedAtEpochMillis}:${it.localKey}" } ?: "-"
}

internal fun restoreMediaTransferCursorHistory(
    encoded: String,
): List<MediaBackupLedgerCursor?> = runCatching {
    val entries = encoded.split('\n')
    require(entries.isNotEmpty() && entries.size <= MAX_SAVED_MEDIA_TRANSFER_WINDOWS)
    require(entries.first() == "-")
    entries.mapIndexed { index, value ->
        if (index == 0) {
            require(value == "-")
            null
        } else {
            val separator = value.indexOf(':')
            require(separator > 0 && separator < value.lastIndex)
            MediaBackupLedgerCursor(
                updatedAtEpochMillis = value.substring(0, separator).toLong(),
                localKey = value.substring(separator + 1),
            )
        }
    }
}.getOrElse { listOf(null) }

fun MediaBackupLedgerSummary.count(section: MediaTransferSection): Int = when (section) {
    MediaTransferSection.Pending -> pending
    MediaTransferSection.Active -> uploading
    MediaTransferSection.Failed -> failed
    MediaTransferSection.Completed -> succeeded
}

data class MediaTransferCenterPage(
    val section: MediaTransferSection,
    val records: List<MediaBackupLedgerRecord>,
    val nextCursor: MediaBackupLedgerCursor?,
) {
    init {
        require(records.size <= MEDIA_TRANSFER_CENTER_PAGE_SIZE)
        require(records.all { it.transferState == section.transferState() })
        require(records.map(MediaBackupLedgerRecord::localKey).distinct().size == records.size)
    }
}

enum class MediaTransferAction {
    Details,
    Retry,
    Cancel,
}

internal val PRODUCTION_MEDIA_TRANSFER_ACTIONS: Set<MediaTransferAction> =
    setOf(MediaTransferAction.Details)

fun MediaBackupLedgerRecord.availableTransferActions(): Set<MediaTransferAction> = when (transferState) {
    MediaBackupTransferState.Pending -> setOf(MediaTransferAction.Details, MediaTransferAction.Cancel)
    MediaBackupTransferState.Uploading -> setOf(MediaTransferAction.Details, MediaTransferAction.Cancel)
    MediaBackupTransferState.Failed -> setOf(MediaTransferAction.Details, MediaTransferAction.Retry)
    MediaBackupTransferState.Succeeded -> setOf(MediaTransferAction.Details)
}

data class MediaTransferCenterState(
    val summary: MediaBackupLedgerSummary,
    val page: MediaTransferCenterPage,
    val canLoadNewer: Boolean,
) {
    init {
        require(page.records.size <= MEDIA_TRANSFER_CENTER_PAGE_SIZE)
    }
}

fun mediaTransferCenterState(
    summary: MediaBackupLedgerSummary,
    section: MediaTransferSection,
    page: MediaBackupLedgerPage,
    canLoadNewer: Boolean,
): MediaTransferCenterState = MediaTransferCenterState(
    summary = summary,
    page = MediaTransferCenterPage(
        section = section,
        records = page.records,
        nextCursor = page.nextCursor,
    ),
    canLoadNewer = canLoadNewer,
)

fun MediaBackupLedgerRecord.resolveMediaBackupStatus(): MediaBackupStatus = when {
    transferState == MediaBackupTransferState.Uploading -> MediaBackupStatus.Uploading
    transferState == MediaBackupTransferState.Failed -> MediaBackupStatus.Failed
    local == null -> MediaBackupStatus.CloudOnly
    receipt == null -> MediaBackupStatus.Pending
    receipt.matches(local) -> MediaBackupStatus.BackedUp
    else -> MediaBackupStatus.ChangedAfterBackup
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

internal fun MediaBackupReceipt.matches(local: LocalMediaObject): Boolean =
    localKey == local.key && localRevision == local.revision && localSize == local.size

private fun String.isSafeMediaLedgerText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)

internal const val MAX_MEDIA_BACKUP_LEDGER_RECORDS_PER_ACCOUNT = 25_000
internal const val MAX_MEDIA_BACKUP_LEDGER_PAGE_SIZE = 200
const val MEDIA_TRANSFER_CENTER_PAGE_SIZE = 50
const val MAX_MEDIA_BACKUP_STATUS_PATHS = 500
const val MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS = 500
const val MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH = 500
internal const val MAX_MEDIA_BACKUP_TRANSFER_SOURCES = 64
internal const val MAX_MEDIA_BACKUP_SOURCE_LEGACY_KEYS = 40_000
private const val MAX_SAVED_MEDIA_TRANSFER_WINDOWS = 64
private const val MAX_MEDIA_BACKUP_ATTEMPTS = 1_000
