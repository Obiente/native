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
}

internal fun resumeDesktopFileSyncUpload(
    staged: File,
    relativePath: String,
    exactLocal: LocalSyncEntry,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    remote: DesktopFileSyncRemoteTree,
): RemoteSyncEntry = jvmResumableNextcloudUpload(
    source = staged,
    relativePath = relativePath,
    localRevision = exactLocal.revision,
    expectedRemoteEtag = expectedRemoteEtag,
    checkpoint = checkpoint,
    newUploadId = { UUID.randomUUID().toString() },
    persistCheckpoint = persistCheckpoint,
    remote = remote.resumableUploadRemote(),
)
