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
        store.saveExecutionTransition(
            state, pairId, workId, work,
            uploadCleanupChange = DesktopFileSyncUploadCleanupChange.Retain(cleanup),
        )
    }

    fun completeCleanup(uploadId: String) {
        state = state.copy(
            coordinator = completeFileSyncUploadCleanup(state.coordinator, pairId, uploadId),
        )
        val work = state.coordinator.pairs.single().workItems.single()
        store.saveExecutionTransition(
            state, pairId, workId, work,
            uploadCleanupChange = DesktopFileSyncUploadCleanupChange.Complete(uploadId),
        )
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
    replacingDirectoryEtag: String? = null,
): RemoteSyncEntry = jvmResumableNextcloudUpload(
    source = staged,
    relativePath = relativePath,
    localRevision = exactLocal.revision,
    expectedRemoteEtag = expectedRemoteEtag,
    checkpoint = checkpoint,
    newUploadId = { UUID.randomUUID().toString() },
    persistCheckpoint = persistCheckpoint,
    remote = remote.resumableUploadRemote(shouldContinue, replacingDirectoryEtag),
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
    val expectedStageContentHash = source.inputStream().buffered().use { input ->
        hashExactJvmFileSyncContent(
            input = input,
            expectedBytes = source.length(),
            shouldContinue = shouldContinue,
        )
    }
    val ownership = FileSyncPendingUploadCleanup(
        uploadId = uploadId,
        relativePath = relativePath,
        replacementBackupEtag = expectedRemoteEtag,
        expectedStageSizeBytes = source.length(),
        expectedStageContentHash = expectedStageContentHash,
    )
    retainCleanup(ownership)
    val published = remote.replaceWithFile(
        relativePath, source, expectedRemoteEtag, uploadId, shouldContinue,
    ) { stageEtag ->
        retainCleanup(
            ownership.copy(
                assembledStageEtag = stageEtag,
                publicationInFlight = true,
            ),
        )
    }
    val publication = remote.resumableUploadRemote(shouldContinue, expectedRemoteEtag)
    val verified = publication.verifyPublishedFile(uploadId, source, relativePath, published)
    publication.completePublishedFile(uploadId, relativePath)
    completeCleanup(uploadId)
    return verified
}

internal fun executeDesktopFileSyncUpload(
    source: File,
    relativePath: String,
    exactLocal: LocalSyncEntry,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    replacingType: Boolean,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    retainCleanup: (FileSyncPendingUploadCleanup) -> Unit,
    completeCleanup: (String) -> Unit,
    remote: DesktopFileSyncRemoteTree,
    shouldContinue: () -> Boolean,
): RemoteSyncEntry {
    if (!replacingType) {
        return resumeDesktopFileSyncUpload(
            source, relativePath, exactLocal, expectedRemoteEtag, checkpoint,
            persistCheckpoint, remote, shouldContinue,
        )
    }
    val expectedDirectoryEtag = requireNotNull(expectedRemoteEtag)
    val transferPlan = nextcloudUploadTransferPlan(source.length())
    if (transferPlan is NextcloudUploadTransferPlan.Chunked) {
        val recoveringPublication = checkpoint?.let {
            it.commitInFlight && it.localRevision == exactLocal.revision && it.transferPlan == transferPlan
        } == true
        if (!recoveringPublication) remote.requireDirectoryGeneration(relativePath, expectedDirectoryEtag)
        return resumeDesktopFileSyncUpload(
            source, relativePath, exactLocal, expectedDirectoryEtag, checkpoint,
            persistCheckpoint, remote, shouldContinue,
            replacingDirectoryEtag = expectedDirectoryEtag,
        )
    }
    remote.requireDirectoryGeneration(relativePath, expectedDirectoryEtag)
    return replaceDesktopFileSyncRemoteType(
        source, relativePath, expectedDirectoryEtag, remote,
        retainCleanup, completeCleanup, shouldContinue,
    )
}
