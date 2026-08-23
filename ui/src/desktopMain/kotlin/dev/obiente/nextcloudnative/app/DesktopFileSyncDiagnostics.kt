package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

internal class DesktopFileSyncScanStoppedException : RuntimeException()

internal class DesktopFileSyncHttpStatusException(
    val statusCode: Int,
    operation: String,
) : RuntimeException("Could not $operation (HTTP $statusCode).") {
    init {
        require(statusCode in 100..599)
    }
}

internal class DesktopFileSyncAmbiguousMutationException(cause: IOException) : IOException(
    "The remote sync mutation ended without a verified result.",
    cause,
)

internal class DesktopFileSyncScanLimitException(
    val maximumEntries: Int,
    val observedEntries: Int,
    val observedFiles: Int,
    val observedFileBytes: Long,
) : IllegalArgumentException(
    "This folder has more than $maximumEntries selected entries. Choose narrower folders or add ignore rules.",
) {
    init {
        require(maximumEntries > 0)
        require(observedEntries > maximumEntries)
        require(observedFiles in 0..observedEntries)
        require(observedFileBytes >= 0L)
    }
}

internal enum class DesktopFileSyncScanStage(val diagnosticValue: String) {
    Local("local"),
}

internal data class DesktopFileSyncRunDiagnosticEvent(
    val pairId: String,
    val stage: DesktopFileSyncScanStage,
    val outcome: String,
    val entryCountBucket: String,
    val fileCountBucket: String,
    val fileBytesBucket: String,
)

internal data class DesktopFileSyncSnapshotDiagnostics(
    val localEntryCountBucket: String,
    val remoteEntryCountBucket: String,
    val localFileBytesBucket: String,
    val remoteFileBytesBucket: String,
) {
    companion object {
        val Unknown = DesktopFileSyncSnapshotDiagnostics("unknown", "unknown", "unknown", "unknown")
    }
}

internal fun DesktopFileSyncScanLimitException.toDesktopFileSyncRunDiagnosticEvent(
    pairId: String,
    stage: DesktopFileSyncScanStage,
) = DesktopFileSyncRunDiagnosticEvent(
    pairId = pairId,
    stage = stage,
    outcome = "limit_exceeded",
    entryCountBucket = desktopFileSyncCountBucket(observedEntries),
    fileCountBucket = desktopFileSyncCountBucket(observedFiles),
    fileBytesBucket = desktopFileSyncByteBucket(observedFileBytes),
)

internal fun desktopFileSyncSnapshotDiagnostics(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
) = DesktopFileSyncSnapshotDiagnostics(
    localEntryCountBucket = desktopFileSyncCountBucket(localEntries.size),
    remoteEntryCountBucket = desktopFileSyncCountBucket(remoteEntries.size),
    localFileBytesBucket = desktopFileSyncByteBucket(localEntries.saturatingLocalFileBytes()),
    remoteFileBytesBucket = desktopFileSyncByteBucket(remoteEntries.saturatingRemoteFileBytes()),
)

internal fun DesktopFileSyncRunDiagnosticEvent.toSupportDiagnosticEventDraft() = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Error,
    component = SupportDiagnosticComponent.Sync,
    operation = "sync.scan",
    outcome = outcome,
    code = "SYNC_SCAN_LIMIT_EXCEEDED",
    message = "A desktop folder sync scan exceeded its selected-entry limit.",
    fields = listOf(
        SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier),
        SupportDiagnosticFieldDraft("stage", stage.diagnosticValue),
        SupportDiagnosticFieldDraft("entry_count_bucket", entryCountBucket),
        SupportDiagnosticFieldDraft("file_count_bucket", fileCountBucket),
        SupportDiagnosticFieldDraft("file_bytes_bucket", fileBytesBucket),
    ),
)

internal fun DesktopFileSyncProgressEvent.toSupportDiagnosticEventDraft(): SupportDiagnosticEventDraft? {
    if (stage != DesktopFileSyncProgressStage.Failed) return null
    return SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.Sync,
        operation = "sync.item",
        outcome = "failed",
        message = "A desktop folder sync item failed.",
        fields = listOf(
            SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier),
            SupportDiagnosticFieldDraft("work", workId.toString(), SupportDiagnosticValuePrivacy.Identifier),
            SupportDiagnosticFieldDraft("operation_type", operation.desktopFileSyncOperationCode()),
            SupportDiagnosticFieldDraft("attempt_count_bucket", desktopFileSyncAttemptBucket(attemptCount)),
            SupportDiagnosticFieldDraft("item_bytes_bucket", desktopFileSyncByteBucket(sizeBytes)),
            SupportDiagnosticFieldDraft("local_entries_bucket", snapshot.localEntryCountBucket),
            SupportDiagnosticFieldDraft("remote_entries_bucket", snapshot.remoteEntryCountBucket),
            SupportDiagnosticFieldDraft("local_file_bytes_bucket", snapshot.localFileBytesBucket),
            SupportDiagnosticFieldDraft("remote_file_bytes_bucket", snapshot.remoteFileBytesBucket),
            SupportDiagnosticFieldDraft("failure_kind", failureKind ?: "unknown"),
        ),
    )
}

internal fun desktopFileSyncFailureKind(failure: Throwable): String = when (failure) {
    is DesktopFileSyncAmbiguousMutationException -> "ambiguous_delivery"
    is DesktopFileSyncHttpStatusException -> when (failure.statusCode) {
        401, 403 -> "authorization"
        409, 412 -> "conflict"
        429 -> "throttled"
        in 500..599 -> "server"
        in 400..499 -> "client"
        else -> "protocol"
    }
    is AccessDeniedException, is SecurityException -> "permission_denied"
    is FileSystemException -> "filesystem"
    is IllegalArgumentException -> "precondition"
    is IllegalStateException -> "state"
    is IOException -> "io"
    else -> "unexpected"
}

internal fun desktopFileSyncCountBucket(count: Int): String = when (count) {
    0 -> "0"
    1 -> "1"
    in 2..9 -> "2-9"
    in 10..99 -> "10-99"
    in 100..999 -> "100-999"
    in 1_000..9_999 -> "1k-9k"
    in 10_000..99_999 -> "10k-99k"
    else -> "100k+"
}

internal fun desktopFileSyncByteBucket(bytes: Long?): String = when (bytes) {
    null -> "unknown"
    0L -> "0"
    in 1L until MIB -> "under-1mib"
    in MIB until 100L * MIB -> "1mib-99mib"
    in 100L * MIB until GIB -> "100mib-999mib"
    in GIB until 10L * GIB -> "1gib-9gib"
    in 10L * GIB until 100L * GIB -> "10gib-99gib"
    else -> "100gib+"
}

internal fun saturatingAdd(left: Long, right: Long): Long {
    require(left >= 0L && right >= 0L)
    return if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

private fun List<LocalSyncEntry>.saturatingLocalFileBytes(): Long = fold(0L) { total, entry ->
    saturatingAdd(total, entry.size ?: 0L)
}

private fun List<RemoteSyncEntry>.saturatingRemoteFileBytes(): Long? {
    var total = 0L
    for (entry in this) {
        if (entry.kind != SyncEntryKind.File) continue
        total = saturatingAdd(total, entry.size ?: return null)
    }
    return total
}

private fun FileSyncOperation.desktopFileSyncOperationCode(): String = when (this) {
    is FileSyncOperation.Upload -> "upload"
    is FileSyncOperation.Download -> "download"
    is FileSyncOperation.DeleteLocal -> "delete_local"
    is FileSyncOperation.DeleteRemote -> "delete_remote"
    is FileSyncOperation.KeepBoth -> "keep_both"
    is FileSyncOperation.NeedsDecision -> "needs_decision"
    is FileSyncOperation.Skipped -> "skipped"
}

private fun desktopFileSyncAttemptBucket(attemptCount: Int): String = when (attemptCount) {
    1 -> "1"
    in 2..3 -> "2-3"
    in 4..5 -> "4-5"
    else -> "6+"
}

private const val MIB = 1_024L * 1_024L
private const val GIB = 1_024L * MIB
