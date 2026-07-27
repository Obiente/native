package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncExecutionState
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncWorkItem
import dev.obiente.nextcloudnative.app.LocalMediaObject
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS
import dev.obiente.nextcloudnative.app.MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH
import dev.obiente.nextcloudnative.app.MediaBackupLedgerKeyMigration
import dev.obiente.nextcloudnative.app.MediaBackupLedgerRecord
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore
import dev.obiente.nextcloudnative.app.MediaBackupReceipt
import dev.obiente.nextcloudnative.app.MediaBackupTransferState
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Mirrors upload-only MediaStore sync work into the indexed media ledger.
 *
 * The folder-sync coordinator remains responsible for execution and revision guards. This bridge
 * gives native media UI a compact account-scoped projection without loading the coordinator's
 * complete work graph.
 */
internal class AndroidMediaBackupLedgerBridge(
    private val pair: FileSyncPair,
    private val store: MediaBackupLedgerStore,
) {
    suspend fun recordVerifiedBaselines(
        baselines: Collection<FileSyncBaseline>,
        localEntries: Collection<LocalSyncEntry>,
        remoteEntries: Collection<RemoteSyncEntry>,
        nowEpochMillis: Long,
    ): Boolean = project {
        val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
        val localPaths = localEntries.mapTo(mutableSetOf(), LocalSyncEntry::relativePath)
        val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
        val verified = localEntries.filter { local ->
            local.kind == SyncEntryKind.File &&
                baselineByPath[local.relativePath]?.localRevision == local.revision
        }
        verified.chunked(MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS).forEach { chunk ->
            val keys = chunk.associateWith { localKey(it.relativePath) }
            val existing = loadExisting(chunk.map(LocalSyncEntry::relativePath), keys.values)
            val records = chunk.mapNotNull { local ->
                val key = requireNotNull(keys[local])
                val baseline = requireNotNull(baselineByPath[local.relativePath])
                mediaBackupVerifiedRecord(
                    pair = pair,
                    local = local,
                    baseline = baseline,
                    existing = existing[key],
                    nowEpochMillis = nowEpochMillis,
                )
            }
            for (batch in records.chunked(MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH)) {
                store.upsertAll(batch)
            }
        }
        val cloudOnly = baselines.filter { baseline ->
            baseline.kind == SyncEntryKind.File &&
                baseline.relativePath !in localPaths &&
                remoteByPath[baseline.relativePath]?.let { remote ->
                    remote.kind == SyncEntryKind.File && remote.etag == baseline.remoteEtag
                } == true
        }
        cloudOnly.chunked(MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS).forEach { chunk ->
            val keys = chunk.associateWith { baseline -> localKey(baseline.relativePath) }
            val existing = loadExisting(chunk.map(FileSyncBaseline::relativePath), keys.values)
            val records = chunk.mapNotNull { baseline ->
                mediaBackupCloudOnlyRecord(
                    pair = pair,
                    baseline = baseline,
                    remote = requireNotNull(remoteByPath[baseline.relativePath]),
                    existing = existing[keys[baseline]],
                    nowEpochMillis = nowEpochMillis,
                )
            }
            for (batch in records.chunked(MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH)) {
                store.upsertAll(batch)
            }
        }
    }

    suspend fun recordPlanned(
        workItems: Collection<FileSyncWorkItem>,
        nowEpochMillis: Long,
    ): Boolean = project {
        val uploads = workItems.filter { work ->
            work.operation is FileSyncOperation.Upload &&
                work.observedLocal?.kind == SyncEntryKind.File &&
                work.state in setOf(
                    FileSyncExecutionState.Ready,
                    FileSyncExecutionState.Running,
                    FileSyncExecutionState.Failed,
                )
        }
        uploads.chunked(MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS).forEach { chunk ->
            val keys = chunk.associateWith { localKey(it.relativePath) }
            val existing = loadExisting(chunk.map(FileSyncWorkItem::relativePath), keys.values)
            val records = chunk.map { work ->
                mediaBackupLedgerRecordForWork(
                    pair = pair,
                    work = work,
                    existing = existing[keys[work]],
                    nowEpochMillis = nowEpochMillis,
                )
            }
            for (batch in records.chunked(MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH)) {
                store.upsertAll(batch)
            }
        }
    }

    suspend fun recordSucceeded(
        work: FileSyncWorkItem,
        local: LocalSyncEntry,
        baseline: FileSyncBaseline,
        nowEpochMillis: Long,
    ): Boolean = project {
        require(work.operation is FileSyncOperation.Upload)
        require(local.kind == SyncEntryKind.File && local.relativePath == work.relativePath)
        require(baseline.relativePath == work.relativePath)
        store.upsert(
            mediaBackupSucceededRecord(pair, work, local, baseline, nowEpochMillis),
        )
    }

    suspend fun close(): Boolean = runMediaBackupProjection {
        store.close()
    }

    private suspend fun project(block: suspend () -> Unit): Boolean {
        val updated = runMediaBackupProjection(block)
        if (updated) MediaBackupStatusUpdates.changes.tryEmit(pair.accountId)
        return updated
    }

    private fun localKey(relativePath: String): String =
        mediaBackupLocalKey(pair.id, pair.localRootId, relativePath)

    private suspend fun loadExisting(
        relativePaths: Collection<String>,
        currentKeys: Collection<String>,
    ): Map<String, MediaBackupLedgerRecord> {
        store.migrateSourceLocalKeys(
            accountId = pair.accountId,
            sourceId = pair.id,
            migrations = relativePaths.map { relativePath ->
                MediaBackupLedgerKeyMigration(
                    legacyLocalKey = legacyMediaBackupLocalKey(pair.localRootId, relativePath),
                    currentLocalKey = localKey(relativePath),
                    remotePath = pair.mediaBackupRemotePath(relativePath),
                )
            },
        )
        return store.loadMany(pair.accountId, currentKeys)
    }

    companion object {
        fun open(context: Context, pair: FileSyncPair): AndroidMediaBackupLedgerBridge? =
            if (
                pair.localRootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX) &&
                pair.configuration.direction == FileSyncDirection.UploadOnly
            ) {
                AndroidMediaBackupLedgerBridge(
                    pair = pair,
                    store = createAndroidMediaBackupLedgerStore(
                        context = context.applicationContext,
                        recoverInterruptedTransfers = false,
                    ),
                )
            } else {
                null
            }
    }
}

internal fun mediaBackupLedgerRecordForWork(
    pair: FileSyncPair,
    work: FileSyncWorkItem,
    existing: MediaBackupLedgerRecord?,
    nowEpochMillis: Long,
): MediaBackupLedgerRecord {
        require(work.operation is FileSyncOperation.Upload)
        val localEntry = requireNotNull(work.observedLocal)
        require(localEntry.kind == SyncEntryKind.File)
        val key = mediaBackupLocalKey(pair.id, pair.localRootId, work.relativePath)
        val localObject = localEntry.toMediaBackupLocalObject(key)
        val path = pair.mediaBackupRemotePath(work.relativePath)
        val baseline = work.observedBaseline
        val validExistingReceipt = existing?.receipt?.takeIf { receipt ->
            receipt.remotePath == path &&
                baseline != null &&
                receipt.localRevision == baseline.localRevision &&
                receipt.remoteEtag == baseline.remoteEtag &&
                work.observedRemote?.etag == baseline.remoteEtag
        }
        val receipt = when {
            baseline != null &&
                baseline.localRevision == localEntry.revision &&
                work.observedRemote?.etag == baseline.remoteEtag ->
                validExistingReceipt ?: MediaBackupReceipt(
                    localKey = key,
                    localRevision = localEntry.revision,
                    localSize = requireNotNull(localEntry.size),
                    remotePath = path,
                    remoteEtag = requireNotNull(baseline.remoteEtag),
                    verifiedAtEpochMillis = nowEpochMillis,
                )
            else -> validExistingReceipt
        }
        val state = when (work.state) {
            FileSyncExecutionState.Ready -> MediaBackupTransferState.Pending
            FileSyncExecutionState.Running -> MediaBackupTransferState.Uploading
            FileSyncExecutionState.Failed -> MediaBackupTransferState.Failed
            FileSyncExecutionState.AwaitingDecision,
            FileSyncExecutionState.Skipped,
            -> error("Only executable upload work belongs in the media ledger.")
        }
        val failure = work.failureMessage.takeIf { state == MediaBackupTransferState.Failed }
        val unchanged = existing?.let { current ->
            current.sourceId == pair.id &&
                current.local == localObject &&
                current.receipt == receipt &&
                current.transferState == state &&
                current.attemptCount == work.attemptCount &&
                current.failureMessage == failure
        } == true
        return MediaBackupLedgerRecord(
            accountId = pair.accountId,
            sourceId = pair.id,
            local = localObject,
            receipt = receipt,
            transferState = state,
            attemptCount = work.attemptCount,
            updatedAtEpochMillis = if (unchanged) requireNotNull(existing).updatedAtEpochMillis else nowEpochMillis,
            failureMessage = failure,
        )
}

internal fun mediaBackupSucceededRecord(
    pair: FileSyncPair,
    work: FileSyncWorkItem,
    local: LocalSyncEntry,
    baseline: FileSyncBaseline,
    nowEpochMillis: Long,
): MediaBackupLedgerRecord {
    require(work.operation is FileSyncOperation.Upload)
    require(local.kind == SyncEntryKind.File && local.relativePath == work.relativePath)
    require(baseline.relativePath == work.relativePath)
    val key = mediaBackupLocalKey(pair.id, pair.localRootId, work.relativePath)
    val localObject = local.toMediaBackupLocalObject(key)
    return MediaBackupLedgerRecord(
        accountId = pair.accountId,
        sourceId = pair.id,
        local = localObject,
        receipt = MediaBackupReceipt(
            localKey = key,
            localRevision = local.revision,
            localSize = requireNotNull(local.size),
            remotePath = pair.mediaBackupRemotePath(work.relativePath),
            remoteEtag = requireNotNull(baseline.remoteEtag),
            verifiedAtEpochMillis = nowEpochMillis,
        ),
        transferState = MediaBackupTransferState.Succeeded,
        attemptCount = work.attemptCount,
        updatedAtEpochMillis = nowEpochMillis,
    )
}

internal fun mediaBackupVerifiedRecord(
    pair: FileSyncPair,
    local: LocalSyncEntry,
    baseline: FileSyncBaseline,
    existing: MediaBackupLedgerRecord?,
    nowEpochMillis: Long,
): MediaBackupLedgerRecord? {
    require(local.kind == SyncEntryKind.File && baseline.kind == SyncEntryKind.File)
    require(local.relativePath == baseline.relativePath && local.revision == baseline.localRevision)
    val key = mediaBackupLocalKey(pair.id, pair.localRootId, local.relativePath)
    val localObject = local.toMediaBackupLocalObject(key)
    val currentReceipt = existing?.receipt
    val path = pair.mediaBackupRemotePath(local.relativePath)
    if (
        existing?.sourceId == pair.id &&
        existing.local == localObject &&
        currentReceipt?.localRevision == local.revision &&
        currentReceipt.localSize == local.size &&
        currentReceipt.remotePath == path &&
        currentReceipt.remoteEtag == baseline.remoteEtag &&
        existing.transferState == MediaBackupTransferState.Succeeded
    ) {
        return null
    }
    return MediaBackupLedgerRecord(
        accountId = pair.accountId,
        sourceId = pair.id,
        local = localObject,
        receipt = MediaBackupReceipt(
            localKey = key,
            localRevision = local.revision,
            localSize = requireNotNull(local.size),
            remotePath = path,
            remoteEtag = requireNotNull(baseline.remoteEtag),
            verifiedAtEpochMillis = currentReceipt
                ?.takeIf {
                    it.localRevision == local.revision &&
                        it.remoteEtag == baseline.remoteEtag
                }
                ?.verifiedAtEpochMillis
                ?: nowEpochMillis,
        ),
        transferState = MediaBackupTransferState.Succeeded,
        attemptCount = existing?.attemptCount ?: 0,
        updatedAtEpochMillis = nowEpochMillis,
    )
}

internal fun mediaBackupCloudOnlyRecord(
    pair: FileSyncPair,
    baseline: FileSyncBaseline,
    remote: RemoteSyncEntry,
    existing: MediaBackupLedgerRecord?,
    nowEpochMillis: Long,
): MediaBackupLedgerRecord? {
    require(baseline.kind == SyncEntryKind.File && remote.kind == SyncEntryKind.File)
    require(baseline.relativePath == remote.relativePath)
    val receipt = existing?.receipt ?: return null
    if (
        receipt.remotePath != pair.mediaBackupRemotePath(baseline.relativePath) ||
        receipt.remoteEtag != baseline.remoteEtag ||
        remote.etag != baseline.remoteEtag
    ) {
        return null
    }
    if (
        existing.sourceId == pair.id &&
        existing.local == null &&
        existing.transferState == MediaBackupTransferState.Succeeded
    ) {
        return null
    }
    return existing.copy(
        sourceId = pair.id,
        local = null,
        transferState = MediaBackupTransferState.Succeeded,
        updatedAtEpochMillis = nowEpochMillis,
        failureMessage = null,
    )
}

private fun LocalSyncEntry.toMediaBackupLocalObject(key: String): LocalMediaObject =
    LocalMediaObject(
        key = key,
        displayName = relativePath.substringAfterLast('/'),
        size = requireNotNull(size),
        revision = revision,
    )

private fun FileSyncPair.mediaBackupRemotePath(relativePath: String): String =
    if (remoteRootPath.isBlank()) relativePath else "$remoteRootPath/$relativePath"

internal fun mediaBackupLocalKey(pairId: String, localRootId: String, relativePath: String): String {
    require(pairId.isNotBlank() && localRootId.isNotBlank() && relativePath.isNotBlank())
    return mediaBackupKeyDigest("${pairId.length}:$pairId${localRootId.length}:$localRootId$relativePath")
}

internal fun legacyMediaBackupLocalKey(localRootId: String, relativePath: String): String {
    require(localRootId.isNotBlank() && relativePath.isNotBlank())
    return mediaBackupKeyDigest("${localRootId.length}:$localRootId$relativePath")
}

private fun mediaBackupKeyDigest(identity: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.encodeToByteArray())
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

internal suspend fun <T> withAndroidMediaBackupLedger(
    context: Context,
    pair: FileSyncPair,
    block: suspend (AndroidMediaBackupLedgerBridge?) -> T,
): T {
    val bridge = try {
        AndroidMediaBackupLedgerBridge.open(context, pair)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }
    return try {
        block(bridge)
    } finally {
        bridge?.close()
    }
}

/**
 * Keeps the media ledger an eventual UI projection. Sync completion and WebDAV writes remain
 * authoritative even if the local projection cannot be opened or updated.
 */
internal suspend fun runMediaBackupProjection(block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }

internal object MediaBackupStatusUpdates {
    val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
}
