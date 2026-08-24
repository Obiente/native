package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Strict, versioned persistence for the transport-neutral sync coordinator.
 *
 * Platform stores must atomically publish these bytes. Credentials, absolute local paths, and file
 * contents are deliberately absent. Work persisted as running is restored as failed because an
 * interrupted executor has not supplied a verified completion result. A fresh scan reconciles the
 * postcondition before the retry policy can make that work executable again.
 */
fun encodeFileSyncCoordinatorSnapshot(state: FileSyncCoordinatorState): ByteArray {
    val validated = FileSyncCoordinatorState(state.pairs)
    val snapshot = FileSyncCoordinatorSnapshotV1(
        pairs = validated.pairs.sortedBy(FileSyncPair::id).map(FileSyncPair::toSnapshot),
    )
    return syncCoordinatorJson.encodeToString(snapshot).encodeToByteArray().also { encoded ->
        require(encoded.size <= MAX_FILE_SYNC_SNAPSHOT_BYTES) { "The sync snapshot is too large." }
    }
}

fun decodeFileSyncCoordinatorSnapshot(bytes: ByteArray): FileSyncCoordinatorState {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_SYNC_SNAPSHOT_BYTES) {
        "The sync snapshot has an invalid size."
    }
    val text = bytes.decodeToString()
    require(text.encodeToByteArray().contentEquals(bytes)) { "The sync snapshot is not valid UTF-8." }
    val snapshot = syncCoordinatorJson.decodeFromString<FileSyncCoordinatorSnapshotV1>(text)
    require(snapshot.schemaVersion == FILE_SYNC_SNAPSHOT_VERSION) {
        "The sync snapshot version is unsupported."
    }
    require(snapshot.pairs.size <= MAX_FILE_SYNC_PAIRS) { "The sync snapshot contains too many pairs." }
    return recoverInterruptedFileSyncWork(
        FileSyncCoordinatorState(snapshot.pairs.map(FileSyncPairSnapshotV1::toDomain)),
    )
}

internal fun encodeFileSyncPairRecord(pair: FileSyncPair): ByteArray {
    require(pair.baselines.isEmpty() && pair.workItems.isEmpty())
    return syncCoordinatorJson.encodeToString(pair.toSnapshot()).encodeToByteArray().also { encoded ->
        require(encoded.size <= MAX_FILE_SYNC_PAIR_RECORD_BYTES) { "The sync pair record is too large." }
    }
}

internal fun decodeFileSyncPairRecord(bytes: ByteArray): FileSyncPair {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_SYNC_PAIR_RECORD_BYTES)
    val text = strictSyncRecordText(bytes)
    return syncCoordinatorJson.decodeFromString<FileSyncPairSnapshotV1>(text).toDomain().also { pair ->
        require(pair.baselines.isEmpty() && pair.workItems.isEmpty())
    }
}

internal fun encodeFileSyncBaselineRecord(baseline: FileSyncBaseline): ByteArray =
    syncCoordinatorJson.encodeToString(baseline.toSnapshot()).encodeToByteArray().also { encoded ->
        require(encoded.size <= MAX_FILE_SYNC_ROW_BYTES) { "The sync baseline record is too large." }
    }

internal fun decodeFileSyncBaselineRecord(bytes: ByteArray): FileSyncBaseline {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_SYNC_ROW_BYTES)
    return syncCoordinatorJson.decodeFromString<FileSyncBaselineSnapshotV1>(strictSyncRecordText(bytes)).toDomain()
}

internal fun encodeFileSyncWorkRecord(work: FileSyncWorkItem): ByteArray =
    syncCoordinatorJson.encodeToString(work.toSnapshot()).encodeToByteArray().also { encoded ->
        require(encoded.size <= MAX_FILE_SYNC_ROW_BYTES) { "The sync work record is too large." }
    }

internal fun decodeFileSyncWorkRecord(bytes: ByteArray): FileSyncWorkItem {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_SYNC_ROW_BYTES)
    return syncCoordinatorJson.decodeFromString<FileSyncWorkSnapshotV1>(strictSyncRecordText(bytes)).toDomain()
}

private fun strictSyncRecordText(bytes: ByteArray): String = bytes.decodeToString().also { text ->
    require(text.encodeToByteArray().contentEquals(bytes)) { "The sync database record is not valid UTF-8." }
}

@Serializable
private data class FileSyncCoordinatorSnapshotV1(
    val schemaVersion: Int = FILE_SYNC_SNAPSHOT_VERSION,
    val pairs: List<FileSyncPairSnapshotV1>,
)

@Serializable
private data class FileSyncPairSnapshotV1(
    val id: String,
    val accountId: String,
    val localRootId: String,
    val remoteRootPath: String,
    val direction: String,
    val conflictPolicy: String,
    val deletionPolicy: String,
    val deviceLabel: String,
    val networkPolicy: String = FileSyncNetworkPolicy.AnyConnection.name,
    val powerPolicy: String = FileSyncPowerPolicy.BatteryNotLow.name,
    val selectedPaths: List<String> = emptyList(),
    val ignoredPatterns: List<String> = emptyList(),
    val priorityPatterns: List<String> = emptyList(),
    val baselines: List<FileSyncBaselineSnapshotV1>,
    val workItems: List<FileSyncWorkSnapshotV1>,
    val nextWorkId: Long,
    val lastScanEpochMillis: Long?,
)

@Serializable
private data class FileSyncBaselineSnapshotV1(
    val relativePath: String,
    val kind: String,
    val localRevision: String?,
    val remoteEtag: String?,
    val contentHash: String? = null,
)

@Serializable
private data class LocalSyncEntrySnapshotV1(
    val relativePath: String,
    val kind: String,
    val revision: String,
    val size: Long?,
    val contentHash: String? = null,
    val modifiedEpochMillis: Long? = null,
)

@Serializable
private data class RemoteSyncEntrySnapshotV1(
    val relativePath: String,
    val kind: String,
    val etag: String,
    val size: Long?,
    val contentHash: String? = null,
    val modifiedEpochMillis: Long? = null,
)

@Serializable
private data class FileSyncWorkSnapshotV1(
    val id: Long,
    val relativePath: String,
    val observedLocal: LocalSyncEntrySnapshotV1?,
    val observedRemote: RemoteSyncEntrySnapshotV1?,
    val observedBaseline: FileSyncBaselineSnapshotV1?,
    val operation: FileSyncOperationSnapshotV1,
    val state: String,
    val decision: FileSyncDecisionSnapshotV1?,
    val attemptCount: Int,
    val lastAttemptEpochMillis: Long?,
    val failureMessage: String?,
    val contentMismatchVerified: Boolean = false,
    val contentMismatchLocalHash: String? = null,
)

@Serializable
private data class FileSyncOperationSnapshotV1(
    val type: String,
    val relativePath: String,
    val expectedRevision: String? = null,
    val localConflictPath: String? = null,
    val remoteConflictPath: String? = null,
    val reason: String? = null,
)

@Serializable
private data class FileSyncDecisionSnapshotV1(
    val reason: String,
    val choices: List<String>,
    val state: String,
    val resolvedChoice: String?,
)

private fun FileSyncPair.toSnapshot(): FileSyncPairSnapshotV1 = FileSyncPairSnapshotV1(
    id = id,
    accountId = accountId,
    localRootId = localRootId,
    remoteRootPath = remoteRootPath,
    direction = configuration.direction.name,
    conflictPolicy = configuration.conflictPolicy.name,
    deletionPolicy = configuration.deletionPolicy.name,
    deviceLabel = configuration.deviceLabel,
    networkPolicy = configuration.networkPolicy.name,
    powerPolicy = configuration.powerPolicy.name,
    selectedPaths = configuration.selectedPaths,
    ignoredPatterns = configuration.ignoredPatterns,
    priorityPatterns = configuration.priorityRules.map(FileSyncPriorityRule::pattern),
    baselines = baselines.sortedBy(FileSyncBaseline::relativePath).map(FileSyncBaseline::toSnapshot),
    workItems = workItems.sortedBy(FileSyncWorkItem::id).map(FileSyncWorkItem::toSnapshot),
    nextWorkId = nextWorkId,
    lastScanEpochMillis = lastScanEpochMillis,
)

private fun FileSyncPairSnapshotV1.toDomain(): FileSyncPair = FileSyncPair(
    id = id,
    accountId = accountId,
    localRootId = localRootId,
    remoteRootPath = remoteRootPath,
    configuration = FileSyncConfiguration(
        direction = enumValueOf(direction),
        conflictPolicy = enumValueOf(conflictPolicy),
        deletionPolicy = enumValueOf(deletionPolicy),
        deviceLabel = deviceLabel,
        networkPolicy = enumValueOf(networkPolicy),
        powerPolicy = enumValueOf(powerPolicy),
        selectedPaths = selectedPaths,
        ignoredPatterns = ignoredPatterns,
        priorityRules = priorityPatterns.map(::FileSyncPriorityRule),
    ),
    baselines = baselines.map(FileSyncBaselineSnapshotV1::toDomain),
    workItems = workItems.map(FileSyncWorkSnapshotV1::toDomain),
    nextWorkId = nextWorkId,
    lastScanEpochMillis = lastScanEpochMillis,
)

private fun FileSyncBaseline.toSnapshot(): FileSyncBaselineSnapshotV1 = FileSyncBaselineSnapshotV1(
    relativePath = relativePath,
    kind = kind.name,
    localRevision = localRevision,
    remoteEtag = remoteEtag,
    contentHash = contentHash,
)

private fun FileSyncBaselineSnapshotV1.toDomain(): FileSyncBaseline = FileSyncBaseline(
    relativePath = relativePath,
    kind = enumValueOf(kind),
    localRevision = localRevision,
    remoteEtag = remoteEtag,
    contentHash = contentHash,
)

private fun LocalSyncEntry.toSnapshot(): LocalSyncEntrySnapshotV1 = LocalSyncEntrySnapshotV1(
    relativePath = relativePath,
    kind = kind.name,
    revision = revision,
    size = size,
    contentHash = contentHash,
    modifiedEpochMillis = modifiedEpochMillis,
)

private fun LocalSyncEntrySnapshotV1.toDomain(): LocalSyncEntry = LocalSyncEntry(
    relativePath = relativePath,
    kind = enumValueOf(kind),
    revision = revision,
    size = size,
    contentHash = contentHash,
    modifiedEpochMillis = modifiedEpochMillis,
)

private fun RemoteSyncEntry.toSnapshot(): RemoteSyncEntrySnapshotV1 = RemoteSyncEntrySnapshotV1(
    relativePath = relativePath,
    kind = kind.name,
    etag = etag,
    size = size,
    contentHash = contentHash,
    modifiedEpochMillis = modifiedEpochMillis,
)

private fun RemoteSyncEntrySnapshotV1.toDomain(): RemoteSyncEntry = RemoteSyncEntry(
    relativePath = relativePath,
    kind = enumValueOf(kind),
    etag = etag,
    size = size,
    contentHash = contentHash,
    modifiedEpochMillis = modifiedEpochMillis,
)

private fun FileSyncWorkItem.toSnapshot(): FileSyncWorkSnapshotV1 = FileSyncWorkSnapshotV1(
    id = id,
    relativePath = relativePath,
    observedLocal = observedLocal?.toSnapshot(),
    observedRemote = observedRemote?.toSnapshot(),
    observedBaseline = observedBaseline?.toSnapshot(),
    operation = operation.toSnapshot(),
    state = state.name,
    decision = decision?.toSnapshot(),
    attemptCount = attemptCount,
    lastAttemptEpochMillis = lastAttemptEpochMillis,
    failureMessage = failureMessage,
    contentMismatchVerified = contentMismatchVerified,
    contentMismatchLocalHash = contentMismatchLocalHash,
)

private fun FileSyncWorkSnapshotV1.toDomain(): FileSyncWorkItem = FileSyncWorkItem(
    id = id,
    relativePath = relativePath,
    observedLocal = observedLocal?.toDomain(),
    observedRemote = observedRemote?.toDomain(),
    observedBaseline = observedBaseline?.toDomain(),
    operation = operation.toDomain(),
    state = enumValueOf(state),
    decision = decision?.toDomain(),
    attemptCount = attemptCount,
    lastAttemptEpochMillis = lastAttemptEpochMillis,
    failureMessage = failureMessage,
    contentMismatchVerified = contentMismatchVerified,
    contentMismatchLocalHash = contentMismatchLocalHash,
)

private fun FileSyncOperation.toSnapshot(): FileSyncOperationSnapshotV1 = when (this) {
    is FileSyncOperation.Upload -> FileSyncOperationSnapshotV1(
        type = "upload",
        relativePath = relativePath,
        expectedRevision = expectedRemoteEtag,
    )
    is FileSyncOperation.Download -> FileSyncOperationSnapshotV1(
        type = "download",
        relativePath = relativePath,
        expectedRevision = expectedLocalRevision,
    )
    is FileSyncOperation.DeleteLocal -> FileSyncOperationSnapshotV1(
        type = "delete-local",
        relativePath = relativePath,
        expectedRevision = expectedLocalRevision,
    )
    is FileSyncOperation.DeleteRemote -> FileSyncOperationSnapshotV1(
        type = "delete-remote",
        relativePath = relativePath,
        expectedRevision = expectedRemoteEtag,
    )
    is FileSyncOperation.KeepBoth -> FileSyncOperationSnapshotV1(
        type = "keep-both",
        relativePath = relativePath,
        localConflictPath = localConflictPath,
        remoteConflictPath = remoteConflictPath,
    )
    is FileSyncOperation.NeedsDecision -> FileSyncOperationSnapshotV1(
        type = "needs-decision",
        relativePath = relativePath,
        reason = reason.name,
    )
    is FileSyncOperation.Skipped -> FileSyncOperationSnapshotV1(
        type = "skipped",
        relativePath = relativePath,
        reason = reason,
    )
}

private fun FileSyncOperationSnapshotV1.toDomain(): FileSyncOperation = when (type) {
    "upload" -> FileSyncOperation.Upload(relativePath, expectedRevision)
    "download" -> FileSyncOperation.Download(relativePath, expectedRevision)
    "delete-local" -> FileSyncOperation.DeleteLocal(relativePath, requireNotNull(expectedRevision))
    "delete-remote" -> FileSyncOperation.DeleteRemote(relativePath, requireNotNull(expectedRevision))
    "keep-both" -> FileSyncOperation.KeepBoth(
        relativePath,
        requireNotNull(localConflictPath),
        requireNotNull(remoteConflictPath),
    )
    "needs-decision" -> FileSyncOperation.NeedsDecision(
        relativePath,
        enumValueOf(requireNotNull(reason)),
    )
    "skipped" -> FileSyncOperation.Skipped(relativePath, requireNotNull(reason))
    else -> error("The sync operation type is unsupported.")
}

private fun FileSyncDecision.toSnapshot(): FileSyncDecisionSnapshotV1 {
    val resolvedChoice = (state as? FileSyncDecisionState.Resolved)?.choice
    return FileSyncDecisionSnapshotV1(
        reason = reason.name,
        choices = choices.map(FileSyncDecisionChoice::name).sorted(),
        state = if (resolvedChoice == null) "pending" else "resolved",
        resolvedChoice = resolvedChoice?.name,
    )
}

private fun FileSyncDecisionSnapshotV1.toDomain(): FileSyncDecision = FileSyncDecision(
    reason = enumValueOf(reason),
    choices = choices.mapTo(linkedSetOf()) { enumValueOf<FileSyncDecisionChoice>(it) },
    state = when (state) {
        "pending" -> {
            require(resolvedChoice == null)
            FileSyncDecisionState.Pending
        }
        "resolved" -> FileSyncDecisionState.Resolved(
            enumValueOf(requireNotNull(resolvedChoice)),
        )
        else -> error("The sync decision state is unsupported.")
    },
)

private val syncCoordinatorJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private const val FILE_SYNC_SNAPSHOT_VERSION = 1
private const val MAX_FILE_SYNC_SNAPSHOT_BYTES = 8 * 1024 * 1024
private const val MAX_FILE_SYNC_PAIR_RECORD_BYTES = 4 * 1024 * 1024
private const val MAX_FILE_SYNC_ROW_BYTES = 128 * 1024
