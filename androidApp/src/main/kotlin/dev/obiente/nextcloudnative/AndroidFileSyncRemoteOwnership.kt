package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.fileSyncOwnedReplacementBackupEtags
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploadPaths
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploads
import dev.obiente.nextcloudnative.app.fileSyncOwnedUploadStageEtags

internal fun androidFileSyncOwnedRemoteTree(
    session: NextcloudSession,
    userId: String,
    pair: FileSyncPair,
    webDav: NextcloudDocumentWebDav,
    transferCancellation: DocumentRequestCancellation = AndroidFileSyncRunCancellation {
        !Thread.currentThread().isInterrupted
    },
): AndroidFileSyncRemoteTree {
    val ownedUploads = fileSyncOwnedUploads(pair)
    return AndroidFileSyncRemoteTree(
        session = session,
        userId = userId,
        remoteRootPath = pair.remoteRootPath,
        webDav = webDav,
        ownedUploadIds = ownedUploads.mapTo(mutableSetOf()) { it.uploadId },
        transferCancellation = transferCancellation,
        ownedStageEtags = fileSyncOwnedUploadStageEtags(pair),
        ownedUploadPaths = fileSyncOwnedUploadPaths(pair),
        ownedReplacementBackupEtags = fileSyncOwnedReplacementBackupEtags(pair),
    )
}
