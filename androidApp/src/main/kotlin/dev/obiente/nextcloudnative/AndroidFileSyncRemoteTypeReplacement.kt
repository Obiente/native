package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.JvmResumableNextcloudUploadRemote
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.NextcloudUploadTransferPlan
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.nextcloudUploadTransferPlan
import java.io.File

internal fun AndroidFileSyncRemoteTree.resumableUploadRemote(
    replacingDirectoryEtag: String? = null,
): JvmResumableNextcloudUploadRemote = replacingDirectoryEtag?.let { expectedDirectoryEtag ->
    AndroidFileSyncDirectoryReplacementUploadRemote(this, expectedDirectoryEtag)
} ?: this

internal fun shouldProtectAndroidFileSyncDirectoryReplacement(
    local: LocalSyncEntry,
    remote: RemoteSyncEntry?,
): Boolean = local.kind == SyncEntryKind.File &&
    remote?.kind == SyncEntryKind.Directory &&
    local.size?.let { nextcloudUploadTransferPlan(it) is NextcloudUploadTransferPlan.Chunked } != false

private class AndroidFileSyncDirectoryReplacementUploadRemote(
    private val tree: AndroidFileSyncRemoteTree,
    private val expectedDirectoryEtag: String,
) : JvmResumableNextcloudUploadRemote by tree {
    override fun uploadDirect(
        source: File,
        relativePath: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry = error("Directory replacement must use an owned upload stage.")

    override fun publishOwnedStage(
        uploadId: String,
        relativePath: String,
        verifiedStageEtag: String,
        expectedRemoteEtag: String?,
    ): RemoteSyncEntry {
        require(expectedRemoteEtag == expectedDirectoryEtag)
        return tree.publishOwnedStageReplacingDirectory(
            uploadId,
            relativePath,
            verifiedStageEtag,
            expectedDirectoryEtag,
        )
    }

    override fun completePublishedFile(uploadId: String, relativePath: String) {
        tree.completeReplacementBackup(relativePath, uploadId)
    }
}
