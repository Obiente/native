package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncUploadCheckpoint
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.checkpointFileSyncUpload
import dev.obiente.nextcloudnative.app.jvmResumableNextcloudUpload
import java.io.File
import java.util.UUID

internal class AndroidFileSyncCheckpointPersistence(
    initialState: AndroidFileSyncPersistedState,
    private val store: AndroidFileSyncStore,
    private val pairId: String,
    private val workId: Long,
) {
    var state: AndroidFileSyncPersistedState = initialState
        private set

    fun persist(checkpoint: FileSyncUploadCheckpoint) {
        state = state.copy(
            coordinator = checkpointFileSyncUpload(state.coordinator, pairId, workId, checkpoint),
        )
        store.save(state)
    }
}

internal fun resumeAndroidFileSyncUpload(
    staged: File,
    relativePath: String,
    exactLocal: LocalSyncEntry,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    remote: AndroidFileSyncRemoteTree,
): RemoteSyncEntry = jvmResumableNextcloudUpload(
    source = staged,
    relativePath = relativePath,
    localRevision = exactLocal.revision,
    expectedRemoteEtag = expectedRemoteEtag,
    checkpoint = checkpoint,
    newUploadId = { UUID.randomUUID().toString() },
    persistCheckpoint = persistCheckpoint,
    remote = remote,
)
