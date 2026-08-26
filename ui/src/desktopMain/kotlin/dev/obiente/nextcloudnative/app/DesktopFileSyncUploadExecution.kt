package dev.obiente.nextcloudnative.app

import java.io.File
import java.util.UUID

internal class DesktopFileSyncCheckpointPersistence(
    initialState: DesktopFileSyncPersistedState,
    private val store: DesktopFileSyncStore,
    private val pairId: String,
    private val workId: Long,
) {
    var state: DesktopFileSyncPersistedState = initialState
        private set

    fun persist(checkpoint: FileSyncUploadCheckpoint) {
        state = state.copy(
            coordinator = checkpointFileSyncUpload(state.coordinator, pairId, workId, checkpoint),
        )
        val work = state.coordinator.pairs.single().workItems.single()
        store.saveExecutionTransition(state, pairId, workId, work)
    }

    fun retainCleanup(cleanup: FileSyncPendingUploadCleanup) {
        state = state.copy(
            coordinator = retainFileSyncUploadCleanup(state.coordinator, pairId, cleanup),
        )
        val work = state.coordinator.pairs.single().workItems.single()
        store.saveExecutionTransition(state, pairId, workId, work)
    }

    fun completeCleanup(uploadId: String) {
        state = state.copy(
            coordinator = completeFileSyncUploadCleanup(state.coordinator, pairId, uploadId),
        )
        val work = state.coordinator.pairs.single().workItems.single()
        store.saveExecutionTransition(state, pairId, workId, work)
    }
}

internal fun resumeDesktopFileSyncUpload(
    staged: File,
    relativePath: String,
    exactLocal: LocalSyncEntry,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    remote: DesktopFileSyncRemoteTree,
    shouldContinue: () -> Boolean,
): RemoteSyncEntry = jvmResumableNextcloudUpload(
    source = staged,
    relativePath = relativePath,
    localRevision = exactLocal.revision,
    expectedRemoteEtag = expectedRemoteEtag,
    checkpoint = checkpoint,
    newUploadId = { UUID.randomUUID().toString() },
    persistCheckpoint = persistCheckpoint,
    remote = remote.resumableUploadRemote(shouldContinue),
    shouldContinue = shouldContinue,
)

internal fun replaceDesktopFileSyncRemoteType(
    source: File,
    relativePath: String,
    expectedRemoteEtag: String,
    remote: DesktopFileSyncRemoteTree,
    retainCleanup: (FileSyncPendingUploadCleanup) -> Unit,
    completeCleanup: (String) -> Unit,
    shouldContinue: () -> Boolean,
): RemoteSyncEntry {
    val uploadId = UUID.randomUUID().toString()
    val ownership = FileSyncPendingUploadCleanup(uploadId, relativePath)
    retainCleanup(ownership)
    return remote.replaceWithFile(
        relativePath, source, expectedRemoteEtag, uploadId, shouldContinue,
    ) { stageEtag ->
        retainCleanup(ownership.copy(assembledStageEtag = stageEtag))
    }.also { completeCleanup(uploadId) }
}
