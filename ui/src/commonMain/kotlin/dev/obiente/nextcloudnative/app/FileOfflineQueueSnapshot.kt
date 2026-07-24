package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned, credential-free persistence format for [FileOfflineQueueState].
 *
 * Platform storage must publish these bytes atomically (temporary file, fsync where available,
 * then replace). Decoding is intentionally strict: malformed or future snapshots never become a
 * partially trusted queue. A job that was persisted as running is re-queued after process restart;
 * no interrupted download or removal is ever assumed to have completed.
 */
internal fun encodeFileOfflineQueueSnapshot(state: FileOfflineQueueState): ByteArray {
    require(state.records.size <= MAX_OFFLINE_SNAPSHOT_RECORDS) {
        "The offline snapshot contains too many records."
    }
    require(state.jobs.size <= MAX_OFFLINE_SNAPSHOT_JOBS) {
        "The offline snapshot contains too many jobs."
    }
    val snapshot = FileOfflineQueueSnapshotV1(
        records = state.records.sortedBy { it.descriptor.key }.map(FileOfflinePinRecord::toSnapshot),
        jobs = state.jobs.sortedBy(FileOfflineJob::id).map(FileOfflineJob::toSnapshot),
        nextJobId = state.nextJobId,
    )
    return offlineSnapshotJson.encodeToString(snapshot).encodeToByteArray().also { encoded ->
        require(encoded.size <= MAX_OFFLINE_SNAPSHOT_BYTES) { "The offline snapshot is too large." }
    }
}

internal fun decodeFileOfflineQueueSnapshot(bytes: ByteArray): FileOfflineQueueState {
    require(bytes.isNotEmpty() && bytes.size <= MAX_OFFLINE_SNAPSHOT_BYTES) {
        "The offline snapshot has an invalid size."
    }
    val text = bytes.decodeToString()
    require(text.encodeToByteArray().contentEquals(bytes)) { "The offline snapshot is not valid UTF-8." }
    val snapshot = offlineSnapshotJson.decodeFromString<FileOfflineQueueSnapshotV1>(text)
    require(snapshot.schemaVersion == FILE_OFFLINE_SNAPSHOT_VERSION) {
        "The offline snapshot version is unsupported."
    }
    require(snapshot.records.size <= MAX_OFFLINE_SNAPSHOT_RECORDS) {
        "The offline snapshot contains too many records."
    }
    require(snapshot.jobs.size <= MAX_OFFLINE_SNAPSHOT_JOBS) {
        "The offline snapshot contains too many jobs."
    }
    return FileOfflineQueueState(
        records = snapshot.records.map(FileOfflinePinSnapshotV1::toDomain),
        jobs = snapshot.jobs.map(FileOfflineJobSnapshotV1::toRecoveredDomain),
        nextJobId = snapshot.nextJobId,
    )
}

@Serializable
private data class FileOfflineQueueSnapshotV1(
    val schemaVersion: Int = FILE_OFFLINE_SNAPSHOT_VERSION,
    val records: List<FileOfflinePinSnapshotV1>,
    val jobs: List<FileOfflineJobSnapshotV1>,
    val nextJobId: Long,
)

@Serializable
private data class FileOfflinePinSnapshotV1(
    val accountId: String,
    val relativePath: String,
    val displayName: String,
    val remoteEtag: String,
    val size: Long?,
    val mimeType: String?,
    val intent: String,
    val localRevision: String?,
    val syncedRemoteEtag: String?,
    val attentionReason: String?,
    val updatedAtEpochMillis: Long,
)

@Serializable
private data class FileOfflineJobSnapshotV1(
    val id: Long,
    val accountId: String,
    val relativePath: String,
    val operation: String,
    val expectedRemoteEtag: String?,
    val expectedLocalRevision: String?,
    val status: String,
    val attemptCount: Int,
    val enqueuedAtEpochMillis: Long,
    val failureMessage: String?,
)

private fun FileOfflinePinRecord.toSnapshot(): FileOfflinePinSnapshotV1 = FileOfflinePinSnapshotV1(
    accountId = descriptor.key.accountId,
    relativePath = descriptor.key.relativePath,
    displayName = descriptor.displayName,
    remoteEtag = descriptor.remoteEtag,
    size = descriptor.size,
    mimeType = descriptor.mimeType,
    intent = intent.name,
    localRevision = localRevision,
    syncedRemoteEtag = syncedRemoteEtag,
    attentionReason = attentionReason?.name,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun FileOfflineJob.toSnapshot(): FileOfflineJobSnapshotV1 = FileOfflineJobSnapshotV1(
    id = id,
    accountId = key.accountId,
    relativePath = key.relativePath,
    operation = operation.name,
    expectedRemoteEtag = expectedRemoteEtag,
    expectedLocalRevision = expectedLocalRevision,
    status = status.name,
    attemptCount = attemptCount,
    enqueuedAtEpochMillis = enqueuedAtEpochMillis,
    failureMessage = failureMessage,
)

private fun FileOfflinePinSnapshotV1.toDomain(): FileOfflinePinRecord = FileOfflinePinRecord(
    descriptor = FileOfflineDescriptor(
        key = FileOfflineKey(accountId, relativePath),
        displayName = displayName,
        remoteEtag = remoteEtag,
        size = size,
        mimeType = mimeType,
    ),
    intent = enumValueOf<FileOfflineIntent>(intent),
    localRevision = localRevision,
    syncedRemoteEtag = syncedRemoteEtag,
    attentionReason = attentionReason?.let { enumValueOf<FileSyncDecisionReason>(it) },
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun FileOfflineJobSnapshotV1.toRecoveredDomain(): FileOfflineJob {
    val restoredStatus = enumValueOf<FileOfflineJobStatus>(status)
    return FileOfflineJob(
        id = id,
        key = FileOfflineKey(accountId, relativePath),
        operation = enumValueOf<FileOfflineJobOperation>(operation),
        expectedRemoteEtag = expectedRemoteEtag,
        expectedLocalRevision = expectedLocalRevision,
        status = if (restoredStatus == FileOfflineJobStatus.Running) {
            FileOfflineJobStatus.Queued
        } else {
            restoredStatus
        },
        attemptCount = attemptCount,
        enqueuedAtEpochMillis = enqueuedAtEpochMillis,
        failureMessage = failureMessage.takeUnless { restoredStatus == FileOfflineJobStatus.Running },
    )
}

private val offlineSnapshotJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private const val FILE_OFFLINE_SNAPSHOT_VERSION = 1
private const val MAX_OFFLINE_SNAPSHOT_BYTES = 4 * 1024 * 1024
private const val MAX_OFFLINE_SNAPSHOT_RECORDS = 10_000
private const val MAX_OFFLINE_SNAPSHOT_JOBS = 10_000
